package com.stackmob.newman.request

import java.net.URL
import _root_.scalaz._
import Scalaz._
import _root_.scalaz.effects._
import com.stackmob.newman.response._
import java.nio.charset.Charset
import com.stackmob.newman.Constants._
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import com.stackmob.common.validation._
import com.stackmob.common.util.casts._
import com.stackmob.newman.{Constants, HttpClient}
import java.security.MessageDigest

/**
 * Created by IntelliJ IDEA.
 *
 * com.stackmob.barney.service.filters.proxy
 *
 * User: aaron
 * Date: 4/24/12
 * Time: 4:58 PM
 */


sealed trait HttpRequest {
  import HttpRequest._
  def url: URL
  def requestType: HttpRequestType
  def headers: Headers
  def prepare: IO[HttpResponse]

  def executeUnsafe: HttpResponse = prepare.unsafePerformIO

  def toJValue(implicit client: HttpClient): JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import com.stackmob.newman.serialization.request.HttpRequestSerialization
    val requestSerialization = new HttpRequestSerialization(client)
    toJSON(this)(requestSerialization.writer)
  }

  def toJson(prettyPrint: Boolean = false)(implicit client: HttpClient) = if(prettyPrint) {
    pretty(render(toJValue))
  } else {
    compact(render(toJValue))
  }

  private lazy val md5 = MessageDigest.getInstance("MD5")

  lazy val hash = {
    val headersString = headers some { headerList: HeaderList =>
      headerList.list.map(h => "%s=%s".format(h._1, h._2)).mkString("&")
    } none { "" }
    val bodyString = new String(this.cast[HttpRequestWithBody].map(_.body) | (HttpRequestWithBody.RawBody.empty), Constants.UTF8Charset)
    val bytes = "%s%s%s".format(url.toString, headersString, bodyString).getBytes(Constants.UTF8Charset)
    md5.digest(bytes)
  }
}

object HttpRequest {
  type Header = (String, String)
  type HeaderList = NonEmptyList[Header]
  type Headers = Option[HeaderList]

  object Headers {
    implicit val HeadersEqual = new Equal[Headers] {
      override def equal(headers1: Headers, headers2: Headers) = (headers1, headers2) match {
        case (Some(h1), Some(h2)) => h1.list === h2.list
        case (None, None) => true
        case _ => false
      }
    }

    def apply(h: Header): Headers = Headers(nel(h))
    def apply(h: Header, tail: Header*): Headers = Headers(nel(h, tail.toList))
    def apply(h: HeaderList): Headers = h.some
    def apply(h: List[Header]): Headers = h.toNel
    def empty = Option.empty[HeaderList]
  }

  def fromJValue(jValue: JValue)(implicit client: HttpClient): Result[HttpRequest] = {
    import com.stackmob.newman.serialization.request.HttpRequestSerialization
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val requestSerialization = new HttpRequestSerialization(client)
    fromJSON(jValue)(requestSerialization.reader)
  }

  def fromJson(json: String)(implicit client: HttpClient) = validating({
    parse(json)
  }).mapFailure({ t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).liftFailNel.flatMap(fromJValue(_))
}

sealed trait HttpRequestWithBody extends HttpRequest {
  import HttpRequestWithBody._
  def body: RawBody
}

object HttpRequestWithBody {
  type RawBody = Array[Byte]

  object RawBody {
    private lazy val emptyBytes = Array[Byte]()
    def empty = emptyBytes
    def apply(s: String, charset: Charset = UTF8Charset) = s.getBytes(charset)
    def apply(b: Array[Byte]) = b
  }

}

trait PostRequest extends HttpRequestWithBody {
  override val requestType = HttpRequestType.POST
}

trait PutRequest extends HttpRequestWithBody {
  override val requestType = HttpRequestType.PUT
}

sealed trait HttpRequestWithoutBody extends HttpRequest
trait DeleteRequest extends HttpRequestWithoutBody {
  override val requestType = HttpRequestType.DELETE
}

trait HeadRequest extends HttpRequestWithoutBody {
  override val requestType = HttpRequestType.HEAD
}

trait GetRequest extends HttpRequestWithoutBody {
  override val requestType = HttpRequestType.GET
}