/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package fr.iscpif.coin.continuous3

import fr.iscpif.coin.agent.Agent

trait ContinuousAgent extends Agent {
  type W = ContinuousWallet
}