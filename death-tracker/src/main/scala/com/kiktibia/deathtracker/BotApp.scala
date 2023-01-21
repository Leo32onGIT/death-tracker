package com.kiktibia.deathtracker

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Category
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData}
import scala.jdk.CollectionConverters._

import scala.concurrent.ExecutionContextExecutor

object BotApp extends App with StrictLogging {
  logger.info("Starting up")

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher

  private val jda = JDABuilder.createDefault(Config.token)
    .addEventListeners(new BotListener())
    .build()

  jda.awaitReady()
  logger.info("JDA ready")

  private val guild: Guild = jda.getGuildById(Config.guildId)

  // initialize hunted lists
  var huntedPlayersList = List.empty[String]
  var huntedGuildsList = List.empty[String]
  var allyPlayersList = List.empty[String]
  var allyGuildsList = List.empty[String]

  // hunted/ally players and Guilds
  val configCategory = getCategoryByName(Config.configChannelsCategory) // this is the name of the 'category' containing the channels
  val huntedPlayersChannel = getTextChannelFromCategory(configCategory, "hunted-players") // this is the name of the channels
  private val huntedGuildsChannel = getTextChannelFromCategory(configCategory, "hunted-guilds")
  private val allyPlayersChannel = getTextChannelFromCategory(configCategory, "allied-players")
  private val allyGuildsChannel = getTextChannelFromCategory(configCategory, "allied-guilds")

  // online list
  val worldCategory = getCategoryByName(Config.worldChannelsCategory) // this is the name of the category' containing the channels
  val onlineAllies = getTextChannelFromCategory(worldCategory, "allies") // this is the name of the channels
  val onlineNeutrals = getTextChannelFromCategory(worldCategory, "neutrals")
  val onlineEnemies = getTextChannelFromCategory(worldCategory, "enemies")
  val inqBlessChannel = getTextChannelFromCategory(worldCategory, "fullbless")

  // levels feed
  val levelsAll = getTextChannelFromCategory(worldCategory, "levels") // this is the name of the channels
  private val deathsChannel = getTextChannelFromCategory(worldCategory, "deaths")
  private val deathTrackerStream = new DeathTrackerStream(deathsChannel)

  // get all messages (max 100) from these channels to compile into the lists
  def getMessagesInChannel(channel: TextChannel): List[String] = {
    val messageHistory = channel.getHistory
    var messages = List.empty[Message]
    var retrieved = messageHistory.retrievePast(100).complete()
    while (!retrieved.isEmpty) {
      messages = messages ++ retrieved.asScala
      retrieved = messageHistory.retrievePast(100).complete()
    }
    if (messages.isEmpty) {
      List.empty
    } else {
      messages.flatMap { message =>
        message.getContentRaw().toLowerCase().replaceAll("`","").trim().split("\n").toList
      }
    }
  }

  // if the player/guild lists need reloading, can be called via slash command
  val command: SlashCommandData = Commands.slash("reload", "reload the ally/hunted players & guilds data")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))

  private val commands = List(command)
  guild.updateCommands().addCommands(commands.asJava).complete()

  def getCategoryByName(categoryName: String): Option[Category] = {
    val categories = jda.getCategories().asScala
    val targetCategory = categories.find(_.getName.toLowerCase() == categoryName)
    targetCategory
  }

  def getTextChannelFromCategory(category: Option[Category], channelName: String): TextChannel = {
    val cat = category.getOrElse(return null)
    val textChannels = cat.getTextChannels().asScala
    val targetChannel = textChannels.find(channel => channel.getName.matches(s"$channelName(-|-[0-9]+)?")).getOrElse(return null)
    targetChannel
  }

  def reload(slash: Boolean = false): Option[MessageEmbed] = {

    huntedPlayersList = getMessagesInChannel(huntedPlayersChannel)
    huntedGuildsList = getMessagesInChannel(huntedGuildsChannel)
    allyPlayersList = getMessagesInChannel(allyPlayersChannel)
    allyGuildsList = getMessagesInChannel(allyGuildsChannel)

    /***
    logger.info(s"Hunted Players: ${huntedPlayersList.toString}")
    logger.info(s"Hunted Guilds: ${huntedGuildsList.toString}")
    logger.info(s"Allied Players: ${allyPlayersList.toString}")
    logger.info(s"Allied Guilds: ${allyGuildsList.toString}")
    ***/
    
    if (slash) {
      val embed = new EmbedBuilder()
      val embedText = s":gear: player & guild config has been reloaded."

      embed.setColor(3092790)
      embed.setDescription(embedText)
      Some(embed.build())
    } else {
      None
    }
  }

  deathTrackerStream.stream.run()
}
