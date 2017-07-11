package velocorner.manual.elastic

import com.sksamuel.elastic4s.ElasticDsl._
import org.slf4s.Logging
import velocorner.model.Activity
import velocorner.util.{ElasticSupport, JsonIo}

import scala.io.Source


object ElasticManual extends App with ElasticSupport with Logging {

  log.info("starting...")

  val client = elasticCluster()

  log.info("reading json entries...")
  val json = Source.fromURL(getClass.getResource("/data/strava/last30activities.json")).mkString
  val activities1 = JsonIo.read[List[Activity]](json)
  val activities = read("last30activities.json", "activity-805296924.json")

  log.info(s"indexing ${activities.size} documents ...")
  val indices = map2Indices(activities)
  client.execute(bulk(indices)).await

  log.info("searching...")
  val res = client.execute(search in "velocorner"->"Ride" query "Uetli" limit 5).await
  log.info(s"found $res")
  res.original.getHits.getHits.headOption.foreach{first =>
    log.info(s"first entry: $first")
  }

  //log.info("counting...")
  //val cres = client.execute(search("velocorner").size(0)).await
  //log.info(s"found ${cres.totalHits}")

  //client.close()

  def read(name: String*): Seq[Activity] = {
    name.map{ resourceName =>
      val json = Source.fromURL(getClass.getResource(s"/data/strava/$resourceName")).mkString
      JsonIo.read[List[Activity]](json)
    }.flatten
  }
}