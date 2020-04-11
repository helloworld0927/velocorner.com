package velocorner.storage

import com.orientechnologies.orient.core.command.OCommandResultListener
import com.orientechnologies.orient.core.db.{ODatabaseType, OrientDB, OrientDBConfig}
import com.orientechnologies.orient.core.db.document.ODatabaseDocument
import com.orientechnologies.orient.core.metadata.schema.{OClass, OType}
import com.orientechnologies.orient.core.record.ORecord
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.query.OSQLNonBlockingQuery
import play.api.libs.json.{Format, Json, Reads, Writes}
import velocorner.model._
import velocorner.model.strava.Club
import velocorner.storage.OrientDbStorage._
import velocorner.util.{CloseableResource, JsonIo, Metrics}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}
import scala.language.implicitConversions
import scala.util.Try
import scala.util.control.Exception._
import scala.concurrent.ExecutionContext.Implicits.global
import com.typesafe.scalalogging.LazyLogging
import scalaz._
import scalaz.std.list._
import scalaz.syntax.functor._
import scalaz.syntax.std.option._
import scalaz.syntax.std.boolean._
import scalaz.std.scalaFuture._
import scalaz.syntax.traverse.ToTraverseOps
import velocorner.api.{Achievement, Activity, Athlete}
import velocorner.api.weather.{SunriseSunset, WeatherForecast}

import scala.jdk.CollectionConverters._

/**
  * Created by levi on 14.11.16.
  * Improvements to do:
  * - use new query API from OrientDB 3.0
  * - use monad stack M[_] : Monad
  * - use parametrized query
  * - use fetch strategy, don't retrieve all the document versions
  * - use compound index for athlete.id and activity type
  */
class OrientDbStorage(url: Option[String], dbPassword: String)
  extends Storage[Future] with CloseableResource with Metrics with LazyLogging {

  @volatile var server: Option[OrientDB] = None
  private val dbUser = url.isDefined ? "root" | "admin"
  private val dbUrl = url.map("remote:" + _).getOrElse("memory:")
  private val dbType = url.isDefined ? ODatabaseType.PLOCAL | ODatabaseType.MEMORY

  private def lookup[T](className: String, propertyName: String, propertyValue: Long)(implicit fjs: Reads[T]): Future[Option[T]] = {
    val sql = s"SELECT FROM $className WHERE $propertyName = $propertyValue"
    queryForOption[T](sql)
  }

  // TODO: workaround until elastic is in place
  def suggestActivities(snippet: String, athleteId: Long, max: Int): Future[Iterable[Activity]] = {
    queryFor[Activity](s"SELECT FROM $ACTIVITY_CLASS WHERE type = 'Ride' AND athlete.id = $athleteId AND name.toLowerCase() like '%${snippet.toLowerCase}%' ORDER BY start_date DESC LIMIT $max")
  }

  // to have an option list all rides for an athlete
  def listActivities(athleteId: Long, activityType: Option[String]): Future[Iterable[Activity]] = {
    val typeClause = activityType.map(a => s"type = '$a' AND ").getOrElse("")
    queryFor[Activity](s"SELECT FROM $ACTIVITY_CLASS WHERE $typeClause athlete.id = $athleteId ORDER BY start_date DESC")
  }

  // insert all activities, new ones are added, previous ones are overridden
  override def storeActivity(activities: Iterable[Activity]): Future[Unit] = {
    activities
      .toList
      .traverseU(a => upsert(a, ACTIVITY_CLASS, s"SELECT FROM $ACTIVITY_CLASS WHERE id = ${a.id}"))
      .void
  }

  override def listActivityTypes(athleteId: Long): Future[Iterable[String]] = Future {
    transact { db =>
      val results = db.query(s"SELECT type AS name, COUNT(*) AS counter FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId GROUP BY name ORDER BY counter DESC")
      results.asScala.map(d => JsonIo.read[Counter](d.toJSON)).map(_.name).to(Iterable)
    }
  }

  override def listAllActivities(athleteId: Long, activityType: String): Future[Iterable[Activity]] =
    queryFor[Activity](s"SELECT FROM $ACTIVITY_CLASS WHERE athlete.id = ? AND type = '$activityType'", athleteId)

  // to check how much needs to be imported from the feed
  override def listRecentActivities(athleteId: Long, limit: Int): Future[Iterable[Activity]] = {
    queryFor[Activity](s"SELECT FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId AND type = 'Ride' ORDER BY start_date DESC LIMIT $limit")
  }

  override def getActivity(id: Long): Future[Option[Activity]] = lookup[Activity](ACTIVITY_CLASS, "id", id)

  // accounts
  override def store(account: Account): Future[Unit] = {
    upsert(account, ACCOUNT_CLASS, s"SELECT FROM $ACCOUNT_CLASS WHERE athleteId = ${account.athleteId}")
  }

  override def getAccount(id: Long): Future[Option[Account]] = lookup[Account](ACCOUNT_CLASS, "athleteId", id)

  // athletes
  override def store(athlete: Athlete): Future[Unit] = {
    upsert(athlete, ATHLETE_CLASS, s"SELECT FROM $ATHLETE_CLASS WHERE id = ${athlete.id}")
  }

  override def getAthlete(id: Long): Future[Option[Athlete]] = lookup[Athlete](ATHLETE_CLASS, "id", id)

  // clubs
  override def store(club: Club): Future[Unit] = {
    upsert(club, CLUB_CLASS, s"SELECT FROM $CLUB_CLASS WHERE id = ${club.id}")
  }

  override def getClub(id: Long): Future[Option[Club]] = lookup[Club](CLUB_CLASS, "id", id)


  lazy val weatherStorage = new WeatherStorage {
    override def listRecentForecast(location: String, limit: Int): Future[Iterable[WeatherForecast]] = {
      queryFor[WeatherForecast](s"SELECT FROM $WEATHER_CLASS WHERE location like '$location' ORDER BY timestamp DESC LIMIT $limit")
    }

    override def storeWeather(forecast: Iterable[WeatherForecast]): Future[Unit] = {
      forecast
        .toList
        .traverseU(a => upsert(a, WEATHER_CLASS, s"SELECT FROM $WEATHER_CLASS WHERE location like '${a.location}' AND timestamp = ${a.timestamp}"))
        .void
    }

    override def getSunriseSunset(location: String, localDate: String): Future[Option[SunriseSunset]] =
      queryForOption[SunriseSunset](s"SELECT FROM $SUN_CLASS WHERE location like '$location' AND date = '$localDate'")

    override def storeSunriseSunset(sunriseSunset: SunriseSunset): Future[Unit] = {
      upsert(sunriseSunset, SUN_CLASS, s"SELECT FROM $SUN_CLASS WHERE location like '${sunriseSunset.location}' AND date = '${sunriseSunset.date}'")
    }
  }

  override def getWeatherStorage: WeatherStorage = weatherStorage

  // attributes
  lazy val attributeStorage = new AttributeStorage {
    override def storeAttribute(key: String, `type`: String, value: String): Future[Unit] = {
      val attr = KeyValue(key, `type`, value)
      upsert(attr, ATTRIBUTE_CLASS, s"SELECT FROM $ATTRIBUTE_CLASS WHERE type = '${`type`}' and key = '$key'")
    }

    override def getAttribute(key: String, `type`: String): Future[Option[String]] = {
      queryForOption[KeyValue](s"SELECT FROM $ATTRIBUTE_CLASS WHERE type = '${`type`}' AND key = '$key'")
        .map(_.map(_.value))
    }
  }

  override def getAttributeStorage: AttributeStorage = attributeStorage

  // various achievements
  lazy val achievementStorage = new AchievementStorage {

    object ResDoubleRow {
      implicit val doubleRowFormat = Format[ResDoubleRow](Json.reads[ResDoubleRow], Json.writes[ResDoubleRow])
    }

    case class ResDoubleRow(res_value: Double)

    object ResLongRow {
      implicit val longRowFormat = Format[ResLongRow](Json.reads[ResLongRow], Json.writes[ResLongRow])
    }

    case class ResLongRow(res_value: Long)

    private def minOf(athleteId: Long, activityType: String, fieldName: String, mapperFunc: Activity => Option[Double], tolerance: Double = .1d): Future[Option[Achievement]] = {
      val result = for {
        _ <- OptionT(queryForOption[ResLongRow](s"SELECT COUNT($fieldName) AS res_value FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId AND type = '$activityType' AND $fieldName IS NOT NULL"))
          .filter(_.res_value > 0L)
        minResult <- OptionT(queryForOption[ResDoubleRow](s"SELECT MIN($fieldName) AS res_value FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId AND type = '$activityType'"))
        _ = logger.debug(s"min[$fieldName]=${minResult.res_value}")
        activity <- OptionT(queryForOption[Activity](s"SELECT FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId AND type = '$activityType' AND $fieldName <= ${minResult.res_value + tolerance} ORDER BY $fieldName ASC LIMIT 1"))
        minValue <- OptionT(Future(mapperFunc(activity)))
      } yield Achievement(
        value = minValue,
        activityId = activity.id,
        activityName = activity.name,
        activityTime = activity.start_date
      )
      result.run
    }

    private def maxOf(athleteId: Long, activityType: String, fieldName: String, mapperFunc: Activity => Option[Double], tolerance: Double = .1d): Future[Option[Achievement]] = {
      val result = for {
        _ <- OptionT(queryForOption[ResLongRow](s"SELECT COUNT($fieldName) AS res_value FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId AND type = '$activityType' AND $fieldName IS NOT NULL"))
          .filter(_.res_value > 0L)
        maxResult <- OptionT(queryForOption[ResDoubleRow](s"SELECT MAX($fieldName) AS res_value FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId AND type = '$activityType'"))
        _ = logger.debug(s"max[$fieldName]=${maxResult.res_value}")
        activity <- OptionT(queryForOption[Activity](s"SELECT FROM $ACTIVITY_CLASS WHERE athlete.id = $athleteId AND type = '$activityType' AND $fieldName >= ${maxResult.res_value - tolerance} ORDER BY $fieldName DESC LIMIT 1"))
        maxValue <- OptionT(Future(mapperFunc(activity)))
      } yield Achievement(
        value = maxValue,
        activityId = activity.id,
        activityName = activity.name,
        activityTime = activity.start_date
      )
      result.run
    }

    override def maxAverageSpeed(athleteId: Long, activity: String): Future[Option[Achievement]] = maxOf(athleteId, activity, "average_speed", _.average_speed.map(_.toDouble))

    override def maxDistance(athleteId: Long, activity: String): Future[Option[Achievement]] = maxOf(athleteId, activity, "distance", _.distance.toDouble.some)

    override def maxElevation(athleteId: Long, activity: String): Future[Option[Achievement]] = maxOf(athleteId, activity, "total_elevation_gain", _.total_elevation_gain.toDouble.some)

    override def maxHeartRate(athleteId: Long, activity: String): Future[Option[Achievement]] = maxOf(athleteId, activity, "max_heartrate", _.max_heartrate.map(_.toDouble))

    override def maxAverageHeartRate(athleteId: Long, activity: String): Future[Option[Achievement]] = maxOf(athleteId, activity, "average_heartrate", _.average_heartrate.map(_.toDouble))

    override def maxAveragePower(athleteId: Long, activity: String): Future[Option[Achievement]] = maxOf(athleteId, activity, "average_watts", _.average_watts.map(_.toDouble))

    override def minAverageTemperature(athleteId: Long, activity: String): Future[Option[Achievement]] = minOf(athleteId, activity, "average_temp", _.average_temp.map(_.toDouble))

    override def maxAverageTemperature(athleteId: Long, activity: String): Future[Option[Achievement]] = maxOf(athleteId, activity, "average_temp", _.average_temp.map(_.toDouble))
  }

  override def getAchievementStorage: AchievementStorage = achievementStorage

  // initializes any connections, pools, resources needed to open a storage session
  override def initialize(): Unit = {
    val orientDb: OrientDB = new OrientDB(dbUrl, dbUser, dbPassword, OrientDBConfig.defaultConfig())
    orientDb.createIfNotExists(DATABASE_NAME, dbType)
    server = orientDb.some

    transact { odb =>
      case class IndexSetup(indexField: String, indexType: OType)

      def createIxIfNeeded(className: String, indexType: OClass.INDEX_TYPE, index: IndexSetup*): Unit = {
        val schema = odb.getMetadata.getSchema
        if (!schema.existsClass(className)) schema.createClass(className)
        val clazz = schema.getClass(className)

        index.foreach(ix =>
          if (!clazz.existsProperty(ix.indexField)) clazz.createProperty(ix.indexField, ix.indexType)
        )

        val ixFields = index.map(_.indexField).sorted
        val ixName = ixFields.mkString("-").replace(".", "_")

        if (!clazz.areIndexed(ixFields: _*)) clazz.createIndex(s"$ixName-$className", indexType, ixFields: _*)
      }

      def dropIx(className: String, ixName: String): Unit = {
        val ixManager = odb.getMetadata.getIndexManager
        // old name was without hyphen, try both versions
        val names = Seq(s"$ixName$className", s"$ixName-$className")
        names.foreach { n =>
          if (ixManager.existsIndex(n)) ixManager.dropIndex(n)
        }
        val schema = odb.getMetadata.getSchema
        val clazz = schema.getClass(className)
        Option(clazz).foreach(_.dropProperty(ixName))
      }

      createIxIfNeeded(ACTIVITY_CLASS, OClass.INDEX_TYPE.UNIQUE, IndexSetup("id", OType.LONG))
      createIxIfNeeded(ACTIVITY_CLASS, OClass.INDEX_TYPE.NOTUNIQUE, IndexSetup("type", OType.STRING))
      createIxIfNeeded(ACTIVITY_CLASS, OClass.INDEX_TYPE.NOTUNIQUE, IndexSetup("athlete.id", OType.LONG))
      createIxIfNeeded(ACCOUNT_CLASS, OClass.INDEX_TYPE.UNIQUE, IndexSetup("athleteId", OType.LONG))
      createIxIfNeeded(CLUB_CLASS, OClass.INDEX_TYPE.UNIQUE, IndexSetup("id", OType.INTEGER))
      createIxIfNeeded(ATHLETE_CLASS, OClass.INDEX_TYPE.UNIQUE, IndexSetup("id", OType.LONG))
      createIxIfNeeded(WEATHER_CLASS, OClass.INDEX_TYPE.UNIQUE, IndexSetup("location", OType.STRING), IndexSetup("timestamp", OType.LONG))
      createIxIfNeeded(SUN_CLASS, OClass.INDEX_TYPE.UNIQUE, IndexSetup("location", OType.STRING), IndexSetup("date", OType.STRING))
      createIxIfNeeded(ATTRIBUTE_CLASS, OClass.INDEX_TYPE.UNIQUE, IndexSetup("key", OType.STRING))
    }
  }

  // releases any connections, resources used
  override def destroy(): Unit = {
    server.foreach(_.close())
    server = None
    logger.info("database has been closed...")
  }

  def transact[T](body: ODatabaseDocument => T): T = {
    server.map { orientDb =>
      val session = orientDb.open(DATABASE_NAME, dbUser, dbPassword)
      session.activateOnCurrentThread()
      ultimately {
        session.close()
      }.apply {
        body(session)
      }
    }.getOrElse(throw new IllegalStateException("database is closed"))
  }

  private def queryFor[T](sql: String, args: Any*)(implicit fjs: Reads[T]): Future[Seq[T]] = Future {
    transact { db =>
      val results = db.query(sql, args.asJava)
      val accuResults = new ListBuffer[T]
      while (results.hasNext) {
        val or = results.next()
        val json = or.toJSON
        accuResults += JsonIo.read[T](json)
      }
      results.close()
      accuResults.toSeq
    }
  }

  private def queryForOption[T](sql: String)(implicit fjs: Reads[T]): Future[Option[T]] = queryFor(sql).map(_.headOption)

  private def upsert[T](payload: T, className: String, sql: String)(implicit fjs: Writes[T]): Future[Unit] = Future {
    val json = JsonIo.write(payload)
    val isUpdate = transact { db =>
      val results = db.query(sql)
      val isUpdate = results.hasNext
      if (isUpdate) {
        val a = results.next()
        //results.close()
        //val el = a.toElement
        //el.fromJSON(JsonIo.write(payload))
        a.getRecord.map { r =>
          val rid = r.getIdentity
          val doc: ODocument = db.load(rid)
          doc.merge(new ODocument(json).setTrackingChanges(false), false, false)
          if (doc.isDirty) doc.save()
          ()
        }
        //el
      } else {
//        val doc = new ODocument(className)
//        doc.fromJSON(json).save()
        //results.close()
        //val doc = new ODocument(className)
        //doc.fromJSON(json).save()
//        val el = db.newElement(className)
//        el.fromJSON(json)
//        el.save()
        //val record: ORecord = el.fromJSON(json)
//        record.map { r =>
//          r.save()
//          ()
//        }
        ()
      }
      //element.fromJSON(JsonIo.write(payload))
      //element.save()
      isUpdate
    }

    if (!isUpdate) transact {_ =>
      val doc = new ODocument(className)
      doc.fromJSON(json).save()
    }
  }
}

object OrientDbStorage {

  val DATABASE_NAME = "velocorner"

  val ACTIVITY_CLASS = "Activity"
  val ACCOUNT_CLASS = "Account"
  val CLUB_CLASS = "Club"
  val ATHLETE_CLASS = "Athlete"
  val WEATHER_CLASS = "Weather"
  val SUN_CLASS = "Sun"
  val ATTRIBUTE_CLASS = "Attribute"

}
