package velocorner.model

import org.joda.time.DateTime
import play.api.libs.json.{Format, Json}

object Achievement {
  implicit val dateTimeFormat = DateTimePattern.createLongFormatter
  implicit val achievmentFormat = Format[Achievement](Json.reads[Achievement], Json.writes[Achievement])
}

case class Achievement(
                        value: Double, // achievemnt value, example max speed, longest ride in km, etc.
                        activityId: Long,
                        activityName: String,
                        activityTime: DateTime
                      )
