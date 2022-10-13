package com.kiktibia.deathtracker

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._

object Config {
  private val root = ConfigFactory.load().getConfig("death-tracker")

  val token: String = root.getString("token")
  val guildId: String = root.getString("guild-id")
  val deathsChannelId: String = root.getString("deaths-channel-id")
  val creatureUrlMappings: Map[String, String] = root.getObject("creature-url-mappings").asScala.map {
    case (k, v) => k -> v.unwrapped().toString
  }.toMap

  // nemesis filter
  val notableCreatures: List[String] = root.getStringList("notable-creatures").asScala.toList
  val nemesisCreatures: List[String] = root.getStringList("nemesis-creatures").asScala.toList
  val archfoeCreatures: List[String] = root.getStringList("archfoe-creatures").asScala.toList
  val baneCreatures: List[String] = root.getStringList("bane-creatures").asScala.toList
  val notableRole: String = root.getString("notable-role")
  val nemesisEmoji: String = root.getString("nemesis-emoji")
  val archfoeEmoji: String = root.getString("archfoe-emoji")
  val baneEmoji: String = root.getString("bane-emoji")
  val summonEmoji: String = root.getString("summon-emoji")

  // hunted
  val huntedGuilds: List[String] = root.getStringList("hunted-guilds").asScala.toList
  val allyGuilds: List[String] = root.getStringList("ally-guilds").asScala.toList
  val huntedPlayers: List[String] = root.getStringList("hunted-players").asScala.toList
  val allyPlayers: List[String] = root.getStringList("ally-players").asScala.toList
  val bossSummons: List[String] = root.getStringList("boss-summons").asScala.toList
  val allyGuild: String = root.getString("allyguild-emoji")
  val otherGuild: String = root.getString("otherguild-emoji")

  // quests
  val mkEmoji: String = root.getString("mk-emoji")
  val mkBosses: List[String] = root.getStringList("mk-bosses").asScala.toList
  val cubeEmoji: String = root.getString("cube-emoji")
  val cubeBosses: List[String] = root.getStringList("cube-bosses").asScala.toList
  val svarGreenEmoji: String = root.getString("svar-green-emoji")
  val svarGreenBosses: List[String] = root.getStringList("svar-green-bosses").asScala.toList
  val svarScrapperEmoji: String = root.getString("svar-scrapper-emoji")
  val svarScrapperBosses: List[String] = root.getStringList("svar-scrapper-bosses").asScala.toList
  val svarWarlordEmoji: String = root.getString("svar-warlord-emoji")
  val svarWarlordBosses: List[String] = root.getStringList("svar-warlord-bosses").asScala.toList
  val zelosEmoji: String = root.getString("zelos-emoji")
  val zelosBosses: List[String] = root.getStringList("zelos-bosses").asScala.toList
  val libEmoji: String = root.getString("library-emoji")
  val libBosses: List[String] = root.getStringList("library-bosses").asScala.toList
  val hodEmoji: String = root.getString("hod-emoji")
  val hodBosses: List[String] = root.getStringList("hod-bosses").asScala.toList
  val feruEmoji: String = root.getString("feru-emoji")
  val feruBosses: List[String] = root.getStringList("feru-bosses").asScala.toList
  val inqEmoji: String = root.getString("inq-emoji")
  val inqBosses: List[String] = root.getStringList("inq-bosses").asScala.toList
  val kilmareshEmoji: String = root.getString("kilmaresh-emoji")
  val kilmareshBosses: List[String] = root.getStringList("kilmaresh-bosses").asScala.toList

  // pvp
  val inqBlessRole: String = root.getString("inqbless-role")
}
