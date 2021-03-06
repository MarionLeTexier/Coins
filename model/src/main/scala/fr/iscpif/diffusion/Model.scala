/*
 * Copyright (C) 2012 Romain Reuillon, Marion Le Texier
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package fr.iscpif.diffusion

import util.Random
import math._
import scala.io.Source
import scalax.io.Resource
import java.io.File

object Model {

  def agentsToCityWallets(agents: Seq[Agent], cities: Seq[City], s: Int): Seq[Seq[Double]] = {
    val wallets =
      agents.groupBy(_.city.id).toList.map {
        case (cityId, agents) =>
          cityId ->
            agents.map {
              a =>
                assert(s != 0 || a.wallet.coins(cities(cityId).country) == 1.0)
                a.wallet.coins
            }.transpose.map { _.sum / agents.size }
      }.toMap
    cities.map(c => wallets(c.id))
  }

  def distance(c1: City, c2: City) = math.hypot(c2.x - c1.x, c2.y - c1.y)

  def readCities(towns: File) = {
    def townMatrix =
      Source.fromFile(towns).getLines.drop(1).filterNot(_.matches(" *")).map {
        l => l.split("\t").toArray
      }

    townMatrix.map {
      line =>
        val id = line(0).toInt
        val country = line(1).toInt
        val population = line(2).toInt
        val x = line(3).toDouble
        val y = line(4).toDouble
        City(id, country, population, x, y)
    }.toIndexedSeq
  }

  def writeCities(cities: Seq[City], towns: File) = {
    towns.delete
    val output = Resource.fromFile(towns)
    output.append("id\tcountry\tpopulation\tx\ty\n")
    for {
      c <- cities
    } output.append(s"${c.id}\t${c.country}\t${c.population}\t${c.x}\t${c.y}\n")
  }

}

import Model._

trait Model <: Exchange {

  /// Decay of the probability to interact with the distance.
  def distanceDecay: Double

  /// Importance of the population on the probability to interact.
  def populationWeight: Double

  /**
   *  Rate of commuters for a city
   * @param city The city of residence.
   * @return The proportion of commuters.
   */
  def mobilRate(city: City): Double

  /// Proportion of exchange according to the number of agents in the city.
  def exchangeRate: Double

  /**
   * Main loop of the model
   * @param cities
   * @param rng
   * @return
   */
  def states(cities: Seq[City])(implicit rng: Random) = {
    // All countries of the model
    val countries = cities.groupBy(_.country).keys.toSeq

    // Create the mutable state of the model
    val agents = initializeAgents(cities, countries.size)

    val agentsBySource = agents.groupBy(_.city)
    val agentsByDestination = agents.groupBy(_.destination)

    def mobilityExchanges() = exchangeInCities(agentsByDestination.toSeq, countries)
    def localExchanges() = exchangeInCities(agentsBySource.toSeq, countries)

    def step(s: Int) = {
      mobilityExchanges()
      localExchanges()
    }

    def copyOfState = agents.map(_.copy)

    Iterator(copyOfState) ++
      Iterator.iterate(0)(_ + 1).map {
        s =>
          step(s)
          copyOfState
      }
  }

  /**
   * Compute the initial state of the model. Creates initial agents states.
   *
   * @param cities The cities simulated by the model.
   * @param nbCountries The number of distinct countries hosting the cities.
   * @param rng A random number generator.
   * @return A list of agents matching an initial state for the model.
   */
  def initializeAgents(cities: Seq[City], nbCountries: Int)(implicit rng: Random): Seq[Agent] = {
    /**
     * Construct commuters and static agents for a given city.
     * @param source Residence city for the agents.
     * @return All the agents of the city.
     */
    def initialAgentsFromCity(source: City) = {
      val commutersValue = commuters(source, cities)

      val commuterDestinations = commutersValue.flatMap {
        case (destination, nbCommuters) => (0 until nbCommuters).map(_ => destination)
      }

      // Commuters are moving to a particular destination (according to the interaction probability matrix)
      val commutersAgents =
        rng.shuffle(commuterDestinations).zipWithIndex.map {
          case (destination, i) =>
            val wallet = Wallet(source, nbCountries)
            Agent(source, destination, wallet)
        }

      // Static agents are only moving within their city of residence
      val staticAgents =
        (0 until source.population - commutersAgents.size).map {
          i =>
            val wallet = Wallet(source, nbCountries)
            Agent(source, source, wallet)
        }
      commutersAgents.toList ::: staticAgents.toList
    }

    cities.flatMap(initialAgentsFromCity).toIndexedSeq
  }

  /**
   * Compute the number of commuters from a given source city to each possible destinations.
   * @param source The source of residence
   * @param cities All the cities
   * @return The number of commuter to each destination city.
   */
  def commuters(source: City, cities: Seq[City]): Seq[(City, Int)] = {
    val nbMobile = round(source.population * mobilRate(source)).toInt
    interactionProbability(source, cities).map {
      case (destination, proportion) =>
        (destination, round(proportion * nbMobile).toInt)
    }
  }

  /**
   * Compute the relative interaction probability between the source city and all the cities. This method implements a Huff model.
   * @param source The city of residence
   * @param cities All the cities
   * @return The interaction probabilities between the source city and all the cities.
   */
  def interactionProbability(source: City, cities: Seq[City]): Seq[(City, Double)] = {
    val otherCities = cities.filterNot(_.id == source.id)
    val absoluteAttractivenesses =
      otherCities.map {
        c =>
          (c, math.pow(distance(source, c), -distanceDecay) * math.pow(c.population, populationWeight))
      }

    val opportunity = absoluteAttractivenesses.unzip._2.sum

    absoluteAttractivenesses.map {
      case (c, attractiveness) => (c, attractiveness / opportunity)
    }
  }

}
