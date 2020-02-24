package controllers

import akka.util.Timeout
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.cache.SyncCacheApi
import play.api.http.Status
import play.api.test.{FakeRequest, Helpers, StubControllerComponentsFactory}
import velocorner.model.strava.Club
import velocorner.storage.Storage

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps


class ActivityControllerSpec extends PlaySpec with StubControllerComponentsFactory with MockitoSugar {

  "rest controller for club activity series" should {

    implicit val timeout = new Timeout(10 seconds)

    "return with success" in {
      val cacheApiMock = mock[SyncCacheApi]
      val settingsMock = mock[ConnectivitySettings]
      val storageMock = mock[Storage[Future]]

      when(settingsMock.getStorage).thenReturn(storageMock)
      when(storageMock.getClub(Club.Velocorner)).thenReturn(Future(None))
      when(storageMock.getAthlete(anyLong())).thenReturn(Future(None))

      val controller = new ActivityController(settingsMock, cacheApiMock, stubControllerComponents())
      val result = controller.ytdProfile("Ride").apply(FakeRequest())
      Helpers.status(result) mustBe Status.OK
    }

    "return with forbidden when asking for activities without being logged in" in {
      val cacheApiMock = mock[SyncCacheApi]
      val settingsMock = mock[ConnectivitySettings]

      val controller = new ActivityController(settingsMock, cacheApiMock, stubControllerComponents())
      val result = controller.activity(100).apply(FakeRequest())
      Helpers.status(result) mustBe Status.FORBIDDEN
    }
  }
}