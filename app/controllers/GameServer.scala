package controllers

import game._
import collection.mutable.ListBuffer
import collection.mutable
import play.api.libs.json._
import scala.util.Random

class GameServer(private val players: Array[PlayerConnection]) {
  players foreach
  (p => {
    p.onReceive = msg => receive(p.player, msg)
    p.onClose = () => playerLeave(p.player)
    if (players.count(_.player.username == p.player.username) > 1)
      p.rename(p.player.username + (1024 + Random.nextInt(1024)).toString)
    p.send(Message("your name", JsString(p.player.username)))
  })

  private var _alive = true
  private val gameState = new GameState(players map (_.player))

  def alive = _alive

  sendBroadband(Message("game start",
                        JsArray(players map (p => p.player.toJson))))

  private def playerLeave(player: Player) =
    gameState.kill(player.username)

  private def receive(player: Player, msg: JsValue) =
    gameState.playerAction(player.username, Orientation.fromString(msg.toString().tail.init))

  private def sendBroadband(msg: JsValue) =
    players foreach (p => p.send(msg))

  private def tick() = {
    gameState.update()
    while (gameState.hasMessages)
      sendBroadband(gameState.popMessage())
    if (gameState.gameOver)
      stop()
  }

  def stop() = {
    _alive = false
    players foreach (p => {
      p.onClose = null
      p.close()
    })
  }
}

object GameServer {
  private class Ticker(val timing: Int) extends Runnable {
    private var servers = mutable.MutableList[GameServer]()
    private var _alive = true
    val thread = new Thread(this)
    thread.start()

    def run() =
      while (_alive) {
        servers = servers.filter(s => {
          s.tick()
          s.alive
        })
        Thread.sleep(timing)
      }

    def kill() =
      _alive = false

    def add(server: GameServer) =
      servers += server

    def alive = _alive

    def size = servers.length
  }

  final val timing = 150
  final var maxServersPerTicker = 4

  private var tickers = ListBuffer(new Ticker(timing))

  def apply(players: Array[PlayerConnection]) = {
    val server = new GameServer(players)
    tickers = tickers.filter(_.alive)
    val minimumLoad = tickers.minBy(_.size)
    if (minimumLoad.size < maxServersPerTicker)
      minimumLoad.add(server)
    else {
      tickers += new Ticker(timing)
      tickers.last.add(server)
    }
    server
  }
}