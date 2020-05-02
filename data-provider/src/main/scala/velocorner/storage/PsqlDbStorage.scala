package velocorner.storage

import cats.effect.IO
import cats.implicits._
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject
import play.api.libs.json.{Reads, Writes}
import velocorner.api.Activity
import velocorner.api.weather.{SunriseSunset, WeatherForecast}
import velocorner.model.Account
import velocorner.util.JsonIo

import scala.concurrent.{ExecutionContext, Future}

class PsqlDbStorage(dbUrl: String, dbUser: String, dbPassword: String) extends Storage[Future] {

  private implicit val cs = IO.contextShift(ExecutionContext.global)
  private lazy val xa = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver", url = dbUrl, user = dbUser, pass = dbPassword
  )

  def playJsonMeta[A: Reads : Writes : Manifest]: Meta[A] = Meta
    .Advanced.other[PGobject]("jsonb")
    .timap[A] { pgo =>
      JsonIo.read[A](pgo.getValue)
    } { json =>
      val o = new PGobject
      o.setType("jsonb")
      o.setValue(JsonIo.write[A](json))
      o
    }

  private implicit val activityMeta: Meta[Activity] = playJsonMeta[Activity]
  private implicit val accountMeta: Meta[Account] = playJsonMeta[Account]
  private implicit val weatherMeta: Meta[WeatherForecast] = playJsonMeta[WeatherForecast]
  private implicit val sunMeta: Meta[SunriseSunset] = playJsonMeta[SunriseSunset]

  implicit class ConnectionIOOps[T](cio: ConnectionIO[T]) {
    def toFuture: Future[T] = cio.transact(xa).unsafeToFuture()
  }

  override def storeActivity(activities: Iterable[Activity]): Future[Unit] =
    activities.map { a =>
      sql"""insert into activity (id, type, athlete_id, data)
           |values(${a.id}, ${a.`type`}, ${a.athlete.id}, $a) on conflict(id)
           |do update set type = ${a.`type`}, athlete_id = ${a.athlete.id}, data = $a
           |""".stripMargin.update.run.void
    }.toList.traverse(identity).void.toFuture

  override def listActivityTypes(athleteId: Long): Future[Iterable[String]] =
    sql"""select type as name, count(*) as counter from activity
         |where athlete_id = $athleteId
         |group by name
         |order by counter desc
         |""".stripMargin.query[String].to[List].toFuture

  override def listAllActivities(athleteId: Long, activityType: String): Future[Iterable[Activity]] =
    sql"""select data from activity
         |where athlete_id = $athleteId and type = $activityType
         |""".stripMargin.query[Activity].to[List].toFuture


  override def listRecentActivities(athleteId: Long, limit: Int): Future[Iterable[Activity]] =
    sql"""select data from activity
         |where athlete_id = $athleteId limit $limit
         |""".stripMargin.query[Activity].to[List].toFuture

  override def suggestActivities(snippet: String, athleteId: Long, max: Int): Future[Iterable[Activity]] = {
    val searchPattern = "%" + snippet.toLowerCase + "%"
    sql"""select data from activity
         |where athlete_id = $athleteId and lower(data->>'name') like $searchPattern
         |order by data->>'start_date' desc
         |limit $max
         |""".stripMargin.query[Activity].to[List].toFuture
  }

  override def getActivity(id: Long): Future[Option[Activity]] =
    sql"""select data from activity where id = $id
         |""".stripMargin.query[Activity].option.toFuture


  override def getAccountStorage: AccountStorage = accountStorage
  private lazy val accountStorage = new AccountStorage {
    override def store(a: Account): Future[Unit] =
      sql"""insert into account (athlete_id, data)
           |values(${a.athleteId}, $a) on conflict(athlete_id)
           |do update set data = $a
           |""".stripMargin.update.run.void.toFuture

  override def getAccount(id: Long): Future[Option[Account]] =
    sql"""select data from account where athlete_id = $id
         |""".stripMargin.query[Account].option.toFuture
    }

  // not used anymore
  override def getAthleteStorage: AthleteStorage = ???

  // not used anymore
  override def getClubStorage: ClubStorage = ???

  override def getWeatherStorage: WeatherStorage = weatherStorage
  private lazy val weatherStorage = new WeatherStorage {
    override def listRecentForecast(location: String, limit: Int): Future[Iterable[WeatherForecast]] =
      sql"""select data from weather
           |where location = $location
           |order by update_time desc
           |limit $limit
           |""".stripMargin.query[WeatherForecast].to[List].toFuture

    override def storeWeather(forecast: Iterable[WeatherForecast]): Future[Unit] =
      forecast.map { a =>
        sql"""insert into weather (location, update_time, data)
             |values(${a.location}, ${a.timestamp}, $a) on conflict(location, update_time)
             |do update set data = $a
             |""".stripMargin.update.run.void
      }.toList.traverse(identity).void.toFuture

    override def getSunriseSunset(location: String, localDate: String): Future[Option[SunriseSunset]] =
      sql"""select data from sun where location = $location and update_date = $localDate
           |""".stripMargin.query[SunriseSunset].option.toFuture

    override def storeSunriseSunset(sunriseSunset: SunriseSunset): Future[Unit] =
      sql"""insert into sun (location, update_date, data)
           |values(${sunriseSunset.location}, ${sunriseSunset.date}, $sunriseSunset) on conflict(location, update_date)
           |do update set data = $sunriseSunset
           |""".stripMargin.update.run.void.toFuture
  }

  override def getAttributeStorage: AttributeStorage = attributeStorage
  private lazy val attributeStorage = new AttributeStorage {
    override def storeAttribute(key: String, `type`: String, value: String): Future[Unit] =
      sql"""insert into attribute (key, type, value)
           |values($key, ${`type`}, $value) on conflict(key, type)
           |do update set value = $value
           |""".stripMargin.update.run.void.toFuture

    override def getAttribute(key: String, `type`: String): Future[Option[String]] =
      sql"""select value from attribute where key = $key and type = ${`type`}
           |""".stripMargin.query[String].option.toFuture
  }

  override def getAchievementStorage: AchievementStorage = ???

  override def initialize(): Unit = {
    val flyway = Flyway.configure().locations("psql/migration").dataSource(dbUrl, dbUser, dbPassword).load()
    flyway.migrate()
  }

  override def destroy(): Unit = {
  }
}
