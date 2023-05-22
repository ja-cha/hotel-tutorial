package com.hotel.tutorial.apps

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.hotel.tutorial.actor.Hotel
import com.hotel.tutorial.model.{CancelReservation, ChangeReservation, RequestReservation}

import java.sql.Date
import java.util.UUID
import scala.concurrent.duration._


object HotelDemo {

  def main(args: Array[String]): Unit = {

    val simpleLogger = Behaviors.receive[Any] { (context, message) =>
      context.log.info(s"[logger] $message")
      Behaviors.same
    }

    val root = Behaviors.setup[String] { context =>
      val logger = context.spawn(simpleLogger, "Logger") // child actor
      val hotel = context.spawn(Hotel("hotel_1"), "hotel_1")

      //make a manual reservation here, or change or cancel it.
      ///hotel.tell(RequestReservation(UUID.randomUUID().toString, Date.valueOf("2023-01-01"), Date.valueOf("2023-01-03"), 1, logger))
      //hotel.tell(ChangeReservation("2VZ5K6JAE5", Date.valueOf("2023-01-1"), Date.valueOf("2023-01-3"), 2, logger))
      //hotel.tell(CancelReservation("2VZ5K6JAE5", logger))

      Behaviors.empty
    }


    val system = ActorSystem(root, "HotelDemo")
    import system.executionContext
    system.scheduler.scheduleOnce(30.seconds, ()=>system.terminate())
  }

}
