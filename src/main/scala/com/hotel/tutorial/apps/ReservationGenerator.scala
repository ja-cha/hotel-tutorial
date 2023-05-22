package com.hotel.tutorial.apps

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.hotel.tutorial.actor.{Hotel, ReservationAgent}
import com.hotel.tutorial.model.{Generate, ManageHotel}

object ReservationGenerator {

  def main(args: Array[String]): Unit = {
    val root = Behaviors.setup[String]{ context =>
      val agent = context.spawn(ReservationAgent(), "agent")
      val hotels = (1 to 100)
        .map(i => s"hotel_$i")
        .map(hotelId => context.spawn(Hotel(hotelId), hotelId))

      hotels.foreach(hotel => agent.tell(ManageHotel(hotel)))

      (1 to 100).foreach{ _ =>
        agent.tell(Generate(15))
        Thread.sleep(100)
      }
      Behaviors.empty
    }

    val system = ActorSystem(root, "ReservationGenerator")

  }

}
