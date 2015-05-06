package scala.reactive



import scala.collection._



/** The `Channel` is the writing end of an isolate.
 *
 *  Every isolate is associated with a channel.
 *  Unlike reactive values or signals, channels can be used
 *  in a thread-safe way -- channels can be shared between
 *  isolates and their methods called from any isolate.
 *
 *  Events cannot be directly sent to a channel.
 *  Instead, reactive values can be attached to channels.
 *  All events that a reactive value produces from that point
 *  end up in the channel.
 *
 *  {{{
 *  val r = new Reactive.Emitter[Int]
 *  c.attach(r)
 *  }}}
 *
 *  Once the `seal` method is called on the channel,
 *  no more reactive values can be attached to it.
 *
 *  {{{
 *  c.seal()
 *  c.attach(r) // this call will not attach a reactive
 *  }}}
 *
 *  The isolate stops once its channel is sealed,
 *  and all the attached reactive values unreact.
 *  This indicates no more events will arrive to the isolate.
 *
 *  @tparam T        the type of events in this channel
 */
trait Channel[@spec(Int, Long, Double) T] {

  /** Attaches a reactive value to this channel.
   *  
   *  This call has no effect if the reactive is already sealed.
   *
   *  @param r       the reactive to attach
   *  @return        this reactive channel, to chain attach calls or call `seal`
   */
  def attach(r: Reactive[T]): Channel[T]

  /** Seals this channel.
   *
   *  Once a seal is successful, no more `attach` calls will succeed.
   *
   *  @return        this reactive channel
   */
  def seal(): Channel[T]

  /** Checks if this channel was sealed.
   *
   *  @return        `true` if the channel is sealed, `false` otherwise
   */
  def isSealed: Boolean

  /** Checks if this channel was terminated.
   *
   *  A channel is terminated if it is sealed and all its reactives are
   *  unreacted.
   *
   *  @return        `true` if the channel is terminated, `false` otherwise
   */
  def isTerminated: Boolean

  /** Creates and attaches a reactive emitter to the channel.
   *
   *  The call `c.attachEmitter()` is equivalent to:
   *
   *  {{{
   *  val e = new Reactive.Emitter[T]
   *  c.attach(e)
   *  e
   *  }}}
   *
   *  @return        the new emitter, just attached to this channel
   */
  def attachEmitter(): Reactive.Emitter[T] = {
    val e = new Reactive.Emitter[T]
    this.attach(e)
    e
  }

  /** Asynchronously sends a single event to this channel.
   *
   *  When multiple events need to be sent,
   *  it is usually **much** more efficient to attach an emitter.
   *  
   *  '''Note''': the channel must not be sealed.
   *  
   *  @param x       the event to send to the channel
   *  @return        this channel
   */
  def <<(x: T): Channel[T] = {
    val e = new Reactive.Emitter[T]
    this.attach(e)
    e.react(x)
    e.unreact()
    this
  }

  /** Creates and attaches a reactive ivar to the channel.
   *
   *  The call `c.attachIvar()` is equivalent to:
   *
   *  {{{
   *  val iv = new Reactive.Ivar[T]
   *  c.attach(iv)
   *  iv
   *  }}}
   *  
   *  @return        the new ivar, just attached to this channel
   */
  def attachIvar(): Reactive.Ivar[T] = {
    val iv = new Reactive.Ivar[T]
    this.attach(iv)
    iv
  }

}


/** Channel implementations and creation methods.
 */
object Channel {

  /** A synchronized channel.
   *
   *  Basic channel implementation for use within a single machine.
   *
   *  @tparam T        type of the channel events
   *  @param reactor   the reactor notified of this channel's events
   *  @param monitor   private monitor object used for synchronization
   */
  class Synced[@spec(Int, Long, Double) T]
    (val reactor: Reactor[T], val monitor: util.Monitor)
  extends Channel[T] {
    private var sealedChannel = false
    private val reactives = mutable.Map[Reactive[T], Reactive.Subscription]()
    def attach(r: Reactive[T]) = monitor.synchronized {
      if (!sealedChannel) {
        if (!reactives.contains(r)) reactives(r) = r.observe(new Reactor[T] {
          def react(event: T) = reactor.react(event)
          def except(t: Throwable) = reactor.except(t)
          def unreact() {
            monitor.synchronized { reactives.remove(r) }
            checkTerminated()
          }
        })
      }
      this
    }
    def seal(): Channel[T] = {
      monitor.synchronized { sealedChannel = true }
      checkTerminated()
      this
    }
    def isSealed = sealedChannel
    def isTerminated = monitor.synchronized {
      sealedChannel && reactives.isEmpty
    }
    private[reactive] def checkTerminated() {
      if (isTerminated) reactor.unreact()
    }
  }

}
