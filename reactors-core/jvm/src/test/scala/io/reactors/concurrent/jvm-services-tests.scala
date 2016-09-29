package io.reactors
package concurrent



import io.reactors.test._
import java.io.InputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import org.apache.commons.io._
import org.scalacheck.Properties
import org.scalacheck.Prop.forAllNoShrink
import org.scalacheck.Gen.choose
import org.scalatest._
import org.scalatest.concurrent.TimeLimitedTests
import scala.collection._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Failure



class NetTest extends FunSuite with Matchers with BeforeAndAfterAll {

  val system = ReactorSystem.default("TestSystem")

  test("resource string should be resolved") {
    val res = Promise[String]()
    val resolver = (url: URL) => IOUtils.toInputStream("ok", "UTF-8")
    system.spawn(Proto[ResourceStringReactor](res, resolver)
      .withScheduler(JvmScheduler.Key.piggyback))
    assert(res.future.value.get.get == "ok", s"got ${res.future.value}")
  }

  test("resource string should throw an exception") {
    val testError = new Exception
    val res = Promise[String]()
    val resolver: URL => InputStream = url => throw testError
    system.spawn(Proto[ResourceStringReactor](res, resolver)
      .withScheduler(JvmScheduler.Key.piggyback))
    assert(res.future.value.get == Failure(testError), s"got ${res.future.value}")
  }

  override def afterAll() {
    system.shutdown()
  }

}


class ResourceStringReactor(val res: Promise[String], val resolver: URL => InputStream)
extends Reactor[Unit] {
  val net = new Platform.Services.Net(system, resolver)
  val response = net.resourceAsString("http://dummy.url/resource.txt")
  response.ignoreExceptions onEvent { s =>
    res success s
    main.seal()
  }
  response onExcept { case t =>
    res failure t
    main.seal()
  }
}


object ChannelsCheck extends Properties("ChannelsCheck") with ExtendedProperties {

  val repetitions = 10
  val nameCounter = new AtomicLong(0L)

  property("channel should be awaited") =
    forAllNoShrink(detChoose(0, 50)) { n =>
      stackTraced {
        for (i <- 0 until repetitions) {
          val checkReactorName = "check-reactor-" + nameCounter.getAndIncrement()
          val system = ReactorSystem.default("check-system")
          val done = Promise[Boolean]()
          system.spawn(Reactor[Unit] { self =>
            system.channels.await[String](checkReactorName + "#main").onEvent { ch =>
              ch ! "done"
              self.main.seal()
            }
          })
          Thread.sleep(n)
          system.spawn(Reactor[String] { self =>
            self.main.events onMatch {
              case "done" =>
                done.success(true)
                self.main.seal()
            }
          } withName(checkReactorName))
          assert(Await.result(done.future, 10.seconds))
        }
        true
      }
    }

}
