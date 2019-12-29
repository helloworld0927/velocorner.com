package velocorner.util

import java.util.zip.GZIPInputStream

import play.api.libs.json._

import scala.io.Source

object JsonIo extends CloseableResource {

  def readFromGzipResource[T](resourceName: String)(implicit fjs: Reads[T]): T = {
    val in = new GZIPInputStream(getClass.getResourceAsStream(resourceName))
    val raw = Source.fromInputStream(in).mkString
    read(raw)
  }

  def readStringFromResource(resourceName: String): String = withCloseable(Source.fromURL(getClass.getResource(resourceName)))(_.mkString)

  def readReadFromResource[T](resourceName: String)(implicit fjs: Reads[T]): T = {
    val json = readStringFromResource(resourceName)
    read[T](json)
  }

  def readFromFile[T](fileName: String)(implicit fjs: Reads[T]): T = {
    val json = withCloseable(Source.fromFile(fileName))(_.mkString)
    read[T](json)
  }
  
  def read[T](json: String)(implicit fjs: Reads[T]): T = {
    val jsonValue = Json.parse(json)
    jsonValue.validate[T] match {
      case JsSuccess(that, _) => that
      case JsError(errors) => sys.error(s"unable to parse file because $errors from $json")
    }
  }

  def write[T](obj: T, pretty: Boolean = true)(implicit fjs: Writes[T]): String = {
    val json = Json.toJson(obj)
    if (pretty) Json.prettyPrint(json) else json.toString()
  }
}
