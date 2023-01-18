package com.kiktibia.deathtracker

import akka.actor.Cancellable
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import akka.stream.{Attributes, Materializer, Supervision}
import com.kiktibia.deathtracker.tibiadata.TibiaDataClient
import com.kiktibia.deathtracker.tibiadata.response.{CharacterResponse, Deaths, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.entities.Channel
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.Webhook
import net.dv8tion.jda.api.entities.Guild
import scala.collection.immutable.ListMap
import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.send.WebhookMessageBuilder

import java.time.ZonedDateTime
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import java.util.Collections

class DeathTrackerStream(deathsChannel: TextChannel)(implicit ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  case class CharKey(char: String, time: ZonedDateTime)
  case class CurrentOnline(name: String, level: Int, vocation: String, guild: String)
  case class CharDeath(char: CharacterResponse, death: Deaths)
  case class CharLevel(name: String, level: Int, vocation: String, lastLogin: ZonedDateTime, time: ZonedDateTime)

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val recentLevels = mutable.Set.empty[CharLevel]
  private val recentOnline = mutable.Set.empty[CharKey]
  private val currentOnline = mutable.Set.empty[CurrentOnline]

  var onlineListTimer = 10
  var onlineListPurgeTimer = 100

  private val tibiaDataClient = new TibiaDataClient()

  private val deathRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off
  private val recentLevelExpiry = 25 * 60 * 60 // 25 hours before deleting recentLevel entry

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the DeathTrackerStream:", e)
    Supervision.Resume
  }
  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)

  private lazy val sourceTick = Source.tick(2.seconds, 20.seconds, ()) // im kinda cow-boying it here

  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info("Running stream")
    tibiaDataClient.getWorld() // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[WorldResponse].mapAsync(1) { worldResponse =>
    val now = ZonedDateTime.now()
    val online: List[String] = worldResponse.worlds.world.online_players.map(_.name)

    // getting online data
    val onlineWithVocLvl = worldResponse.worlds.world.online_players.map { player => (player.name, player.level.toInt, player.vocation, "") }
    currentOnline.addAll(onlineWithVocLvl.map(i => CurrentOnline(i._1, i._2, i._3, i._4)))

    recentOnline.filterInPlace(i => !online.contains(i.char)) // Remove existing online chars from the list...
    recentOnline.addAll(online.map(i => CharKey(i, now))) // ...and add them again, with an updated online time

    val charsToCheck: Set[String] = recentOnline.map(_.char).toSet
    Source(charsToCheck).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).runWith(Sink.collection).map(_.toSet)
  }.withAttributes(logAndResume)

  private lazy val scanForDeaths = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()
    val newDeaths = characterResponses.flatMap { char =>

      // gather guild icons data for online player list
      val charName = char.characters.character.name
      val guild = char.characters.character.guild
      val guildName = if(!(guild.isEmpty)) guild.head.name else ""
      var guildIcon = Config.noGuild
      var levelChannel = BotApp.levelsAll
      if (guildName != "") {
        guildIcon = Config.otherGuild
        val allyGuilds = BotApp.allyGuildsList.contains(guildName.toLowerCase())
        if (allyGuilds == true){
          //levelChannel = BotApp.levelsAllies
          guildIcon = Config.allyGuild
        }
        val huntedGuilds = BotApp.huntedGuildsList.contains(guildName.toLowerCase())
        if (huntedGuilds == true){
          //levelChannel = BotApp.levelsEnemies
          guildIcon = Config.enemyGuild
        }
      }
      val huntedPlayers = BotApp.huntedPlayersList.contains(charName.toLowerCase())
      if (huntedPlayers == true){
        //levelChannel = BotApp.levelsEnemies
        if (guildName != "") {
          guildIcon = Config.enemyGuild
        } else {
          guildIcon = Config.enemy
        }
      }
      val allyPlayers = BotApp.allyPlayersList.contains(charName.toLowerCase())
      if (allyPlayers == true){
        //levelChannel = BotApp.levelsAllies
        guildIcon = Config.allyGuild
      }
      // add the guild icon
      currentOnline.find(_.name == charName).foreach { onlinePlayer =>
        currentOnline -= onlinePlayer
        currentOnline += onlinePlayer.copy(guild = guildIcon)
      }
      // detecting new levels
      val sheetLevel = char.characters.character.level
      val sheetVocation = char.characters.character.vocation
      val sheetLastLogin = ZonedDateTime.parse(char.characters.character.last_login.getOrElse("2022-01-01T01:00:00Z"))
      currentOnline.find(_.name == charName).foreach { onlinePlayer =>
        if (onlinePlayer.level > sheetLevel && now.isAfter(sheetLastLogin.plusMinutes(5))){
          val newCharLevel = CharLevel(charName, onlinePlayer.level, sheetVocation, sheetLastLogin, now)
          val webhookMessage = s"${guildIcon} **[$charName](${charUrl(charName)})** advanced to level **${onlinePlayer.level}** ${vocEmoji(char)}"
          if (recentLevels.exists(x => x.name == charName && x.level == onlinePlayer.level)){
            val lastLoginInRecentLevels = recentLevels.filter(x => x.name == charName && x.level == onlinePlayer.level)
              if (lastLoginInRecentLevels.forall(x => x.lastLogin.isBefore(sheetLastLogin))){
                recentLevels += newCharLevel
                createAndSendWebhookMessage(levelChannel, webhookMessage, s"${Config.worldChannelsCategory.capitalize}")
              }
          } else {
              recentLevels += newCharLevel
              createAndSendWebhookMessage(levelChannel, webhookMessage, s"${Config.worldChannelsCategory.capitalize}")
          }
        }
      }

      val deaths: List[Deaths] = char.characters.deaths.getOrElse(List.empty)
      deaths.flatMap { death =>
        val deathTime = ZonedDateTime.parse(death.time)
        val deathAge = java.time.Duration.between(deathTime, now).getSeconds
        val charDeath = CharKey(char.characters.character.name, deathTime)
        if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
          recentDeaths.add(charDeath)
          Some(CharDeath(char, death))
        }
        else None
      }
    }

    // update online list
    onlineListTimer += 1
    if (onlineListTimer >= 10) {
      onlineListTimer = 0
      val currentOnlineList: List[(String, Int, String, String)] = currentOnline.map { onlinePlayer =>
        (onlinePlayer.name, onlinePlayer.level, onlinePlayer.vocation, onlinePlayer.guild)
      }.toList
      onlineList(currentOnlineList)
    }

    Future.successful(newDeaths)
  }.withAttributes(logAndResume)

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharDeath]].mapAsync(1) { charDeaths =>

    /***
    // Filter only the interesting deaths (nemesis bosses, rare bestiary)
    val (notableDeaths, normalDeaths) = charDeaths.toList.partition { charDeath =>
      Config.notableCreatures.exists(c => c.endsWith(charDeath.death.killers.last.name.toLowerCase))
    }

    // logging
    logger.info(s"New notable deaths: ${notableDeaths.length}")
    notableDeaths.foreach(d => logger.info(s"${d.char.characters.character.name} - ${d.death.killers.last.name}"))
    logger.info(s"New normal deaths: ${normalDeaths.length}")
    normalDeaths.foreach(d => logger.info(s"${d.char.characters.character.name} - ${d.death.killers.last.name}"))


    val embeds = notableDeaths.sortBy(_.death.time).map { charDeath =>
    ***/

    val embeds = charDeaths.toList.sortBy(_.death.time).map { charDeath =>
      var notablePoke = ""
      val charName = charDeath.char.characters.character.name
      val killer = charDeath.death.killers.last.name
      var context = "Died"
      var embedColor = 3092790 // background default
      var embedThumbnail = creatureImageUrl(killer)
      var vowelCheck = "" // this is for adding "an" or "a" in front of creature names
      var killerBuffer = ListBuffer[String]()
      var exivaBuffer = ListBuffer[String]()
      var exivaList = ""
      val killerList = charDeath.death.killers // get all killers

      // guild rank and name
      val guild = charDeath.char.characters.character.guild
      val guildName = if(!(guild.isEmpty)) guild.head.name else ""
      val guildRank = if(!(guild.isEmpty)) guild.head.rank else ""
      //var guildText = ":x: **No Guild**\n"
      var guildText = ""

      // guild
      // does player have guild?
      var guildIcon = Config.otherGuild
      if (guildName != "") {
        // if untracked neutral guild show grey
        if (embedColor == 3092790){
          embedColor = 4540237
        }
        // is player an ally
        val allyGuilds = BotApp.allyGuildsList.contains(guildName.toLowerCase())
        if (allyGuilds == true){
          embedColor = 13773097 // bright red
          guildIcon = Config.allyGuild
        }
        // is player in hunted guild
        val huntedGuilds = BotApp.huntedGuildsList.contains(guildName.toLowerCase())
        if (huntedGuilds == true){
          embedColor = 36941 // bright green
          if (context == "Died" && charDeath.death.level.toInt >= 250) {
            notablePoke = Config.inqBlessRole // PVE fullbless opportuniy (only poke for level 250+)
          }
        }
        guildText = s"$guildIcon *$guildRank* of the [$guildName](https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guildName.replace(" ", "%20")})\n"
      }

      // player
      // ally player
      val allyPlayers = BotApp.allyPlayersList.contains(charName.toLowerCase())
      if (allyPlayers == true){
        embedColor = 13773097 // bright red
      }
      // hunted player
      val huntedPlayers = BotApp.huntedPlayersList.contains(charName.toLowerCase())
      if (huntedPlayers == true){
        embedColor = 36941 // bright green
        if (context == "Died") {
          notablePoke = Config.inqBlessRole // PVE fullbless opportuniy
        }
      }

      // poke if killer is in notable-creatures config
      val poke = Config.notableCreatures.contains(killer.toLowerCase())
      if (poke == true) {
        notablePoke = Config.notableRole
        embedColor = 11563775 // bright purple
      }

      if (killerList.nonEmpty) {
        killerList.foreach { k =>
          if (k.player == true) {
            if (k.name != charName){ // ignore 'self' entries on deathlist
              context = "Killed"
              notablePoke = "" // reset poke as its not a fullbless
              if (embedColor == 3092790 || embedColor == 4540237){
                embedColor = 14869218 // bone white
              }
              embedThumbnail = creatureImageUrl("Phantasmal_Ooze")
              val isSummon = k.name.split(" of ", 2) // e.g: fire elemental of Violent Beams
              if (isSummon.length > 1){
                if (isSummon(0).exists(_.isUpper) == false) { // summons will be lowercase, a player with " of " in their name will have a capital letter
                  val vowel = isSummon(0).take(1) match {
                  case "a" => "an"
                  case "e" => "an"
                  case "i" => "an"
                  case "o" => "an"
                  case "u" => "an"
                  case _ => "a"
                  }
                  killerBuffer += s"$vowel ${Config.summonEmoji} **${isSummon(0)} of [${isSummon(1)}](${charUrl(isSummon(1))})**"
                  if (guildIcon == Config.allyGuild) {
                    exivaBuffer += isSummon(1)
                  }
                } else {
                  killerBuffer += s"**[${k.name}](${charUrl(k.name)})**" // player with " of " in the name e.g: Knight of Flame
                  if (guildIcon == Config.allyGuild) {
                    exivaBuffer += k.name
                  }
                }
              } else {
                killerBuffer += s"**[${k.name}](${charUrl(k.name)})**" // summon not detected
                if (guildIcon == Config.allyGuild) {
                  exivaBuffer += k.name
                }
              }
            }
          } else {
            // custom emojis for flavour
            // map boss lists to their respesctive emojis
            val creatureEmojis: Map[List[String], String] = Map(
              Config.nemesisCreatures -> Config.nemesisEmoji,
              Config.archfoeCreatures -> Config.archfoeEmoji,
              Config.baneCreatures -> Config.baneEmoji,
              Config.bossSummons -> Config.summonEmoji,
              Config.cubeBosses -> Config.cubeEmoji,
              Config.mkBosses -> Config.mkEmoji,
              Config.svarGreenBosses -> Config.svarGreenEmoji,
              Config.svarScrapperBosses -> Config.svarScrapperEmoji,
              Config.svarWarlordBosses -> Config.svarWarlordEmoji,
              Config.zelosBosses -> Config.zelosEmoji,
              Config.libBosses -> Config.libEmoji,
              Config.hodBosses -> Config.hodEmoji,
              Config.feruBosses -> Config.feruEmoji,
              Config.inqBosses -> Config.inqEmoji,
              Config.kilmareshBosses -> Config.kilmareshEmoji
            )
            // assign the appropriate emoji
            val bossIcon = creatureEmojis.find {
              case (creatures, emoji) => creatures.contains(k.name.toLowerCase())
            }.map(_._2).getOrElse("")

            // add "an" or "a" depending on first letter of creatures name
            // ignore capitalized names (nouns) as they are bosses
            // if player dies to a neutral source show 'died by energy' instead of 'died by an energy'
            if (!(k.name.exists(_.isUpper))){
              val elements = List("death", "earth", "energy", "fire", "ice", "holy", "a trap", "agony", "life drain", "drowning")
              vowelCheck = k.name.take(1) match {
                case _ if elements.contains(k.name) => ""
                case "a" => "an "
                case "e" => "an "
                case "i" => "an "
                case "o" => "an "
                case "u" => "an "
                case _ => "a "
              }
            }
            killerBuffer += s"$vowelCheck$bossIcon**${k.name}**"
          }
        }
      }

      if (exivaBuffer.nonEmpty) {
        exivaBuffer.zipWithIndex.foreach { case (exiva, i) =>
          if (i == 0){
            exivaList += s"""\n${Config.exivaEmoji} `exiva "$exiva"`""" // add exiva emoji
          } else {
            exivaList += s"""\n${Config.indentEmoji} `exiva "$exiva"`""" // just use indent emoji for further player names
          }
        }
      }
      // convert formatted killer list to one string
      val killerInit = if (killerBuffer.nonEmpty) killerBuffer.view.init else None
      var killerText =
        if (killerInit.nonEmpty) {
          killerInit.mkString(", ") + " and " + killerBuffer.last
        } else killerBuffer.headOption.getOrElse("")

      // this should only occur to pure suicides on bomb runes, or pure 'assists' deaths in yellow-skull friendy fire or retro/hardcore situations
      if (killerText == ""){
          embedThumbnail = creatureImageUrl("Red_Skull_(Item)")
          killerText = s"""`suicide`"""
      }

      val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond

      // this is the actual embed description
      val embedText = s"$guildText$context <t:$epochSecond:R> at level ${charDeath.death.level.toInt}\nby $killerText.$exivaList"

      val embed = new EmbedBuilder()
      embed.setTitle(s"${vocEmoji(charDeath.char)} $charName ${vocEmoji(charDeath.char)}", charUrl(charName))
      embed.setDescription(embedText)
      embed.setThumbnail(embedThumbnail)
      embed.setColor(embedColor)
      (embed.build(), notablePoke)

    }
    // Send the embeds one at a time, otherwise some don't get sent if sending a lot at once
    embeds.foreach { embed =>
      deathsChannel.sendMessageEmbeds(embed._1).queue()
      if (embed._2 == Config.notableRole){
        deathsChannel.sendMessage(embed._2).queue();
      } else if (embed._2 == Config.inqBlessRole){
        var inqChannel = BotApp.inqBlessChannel
        inqChannel.sendMessageEmbeds(embed._1).queue()
        inqChannel.sendMessage("@here").queue();
      }
    }

    cleanUp()

    Future.successful()
  }.withAttributes(logAndResume)

  //
  private def onlineList(onlineData: List[(String, Int, String, String)]) {

    val vocationBuffers = ListMap(
      "druid" -> ListBuffer[(String, String)](),
      "knight" -> ListBuffer[(String, String)](),
      "paladin" -> ListBuffer[(String, String)](),
      "sorcerer" -> ListBuffer[(String, String)](),
      "none" -> ListBuffer[(String, String)]()
    )

    val sortedList = onlineData.sortWith(_._2 > _._2)
    sortedList.foreach { player =>
      val voc = player._3.toLowerCase.split(' ').last
      val vocEmoji = voc match {
        case "knight" => ":shield:"
        case "druid" => ":snowflake:"
        case "sorcerer" => ":fire:"
        case "paladin" => ":bow_and_arrow:"
        case "none" => ":hatching_chick:"
        case _ => ""
      }
      vocationBuffers(voc) += ((s"${player._4}", s"${player._4} **[${player._1}](${charUrl(player._1)})** — Level ${player._2.toString} $vocEmoji"))
    }

    val alliesList: List[String] = vocationBuffers.values.flatMap(_.filter(_._1 == s"${Config.allyGuild}").map(_._2)).toList
    val neutralsList: List[String] = vocationBuffers.values.flatMap(_.filter { case (first, _) => first == s"${Config.otherGuild}" || first == s"${Config.noGuild}" }.map(_._2)).toList
    val enemiesList: List[String] = vocationBuffers.values.flatMap(_.filter { case (first, _) => first == s"${Config.enemyGuild}" || first == s"${Config.enemy}" }.map(_._2)).toList

    val alliesCount = alliesList.size
    val neutralsCount = neutralsList.size
    val enemiesCount = enemiesList.size

    if (BotApp.onlineAllies.getName() != s"allies-$alliesCount") {
      val channelManager = BotApp.onlineAllies.getManager
      channelManager.setName(s"allies-$alliesCount").queue()
    }
    if (BotApp.onlineNeutrals.getName() != s"neutrals-$neutralsCount") {
      val channelManager = BotApp.onlineNeutrals.getManager
      channelManager.setName(s"neutrals-$neutralsCount").queue()
    }
    if (BotApp.onlineEnemies.getName() != s"enemies-$enemiesCount") {
      val channelManager = BotApp.onlineEnemies.getManager
      channelManager.setName(s"enemies-$enemiesCount").queue()
    }

    onlineListPurgeTimer += 1
    if (alliesList.nonEmpty){
      updateMultiFields(alliesList, BotApp.onlineAllies)
    } else {
      updateMultiFields(List("No allies are online right now."), BotApp.onlineAllies)
    }
    if (neutralsList.nonEmpty){
      updateMultiFields(neutralsList, BotApp.onlineNeutrals)
    } else {
      updateMultiFields(List("No neutrals are online right now."), BotApp.onlineNeutrals)
    }
    if (enemiesList.nonEmpty){
      updateMultiFields(enemiesList, BotApp.onlineEnemies)
    } else {
      updateMultiFields(List("No enemies are online right now."), BotApp.onlineEnemies)
    }
    if (onlineListPurgeTimer >= 100) {
      onlineListPurgeTimer = 0
    }
  }

  def updateMultiFields(values: List[String], channel: TextChannel): Unit = {
    var field = ""
    val embedColor = 3092790
    var messages = channel.getHistory.retrievePast(100).complete()

    // clear the channel every 25 iterations
    if (onlineListPurgeTimer >= 100) {
      channel.purgeMessages(messages)
      messages = List.empty.asJava
    }

    Collections.reverse(messages)
    var currentMessage = 0
    values.foreach { v =>
      val currentField = field + "\n" + v
      if (currentField.length <= 4096) { // don't add field yet, there is still room
        field = currentField
      }
      else { // it's full, add the field
        val interimEmbed = new EmbedBuilder()
        interimEmbed.setDescription(field)
        interimEmbed.setColor(embedColor)
        if (currentMessage < messages.size) {
          // edit the existing message
          messages.get(currentMessage).editMessageEmbeds(interimEmbed.build()).queue()
        }
        else {
          // there isn't an existing message to edit, so post a new one
          channel.sendMessageEmbeds(interimEmbed.build()).queue()
        }
        field = v
        currentMessage += 1
      }
    }
    val finalEmbed = new EmbedBuilder()
    finalEmbed.setDescription(field)
    finalEmbed.setColor(embedColor)
    if (currentMessage < messages.size) {
      // edit the existing message
      messages.get(currentMessage).editMessageEmbeds(finalEmbed.build()).queue()
    }
    else {
      // there isn't an existing message to edit, so post a new one
      channel.sendMessageEmbeds(finalEmbed.build()).queue()
    }
    if (currentMessage < messages.size - 1) {
      // delete extra messages
      val messagesToDelete = messages.subList(currentMessage + 1, messages.size)
      channel.purgeMessages(messagesToDelete)
    }
  }

  // send a webhook to discord (this is used as we can have hyperlinks in Text Messages)
  def createAndSendWebhookMessage(webhookChannel: TextChannel, messageContent: String, messageAuthor: String): Unit = {
    val getWebHook = webhookChannel.retrieveWebhooks().submit().get()
    var webhook: Webhook = null
    if (getWebHook.isEmpty) {
        val createWebhook = webhookChannel.createWebhook(messageAuthor).submit()
        webhook = createWebhook.get()
    } else {
        webhook = getWebHook.get(0)
    }
    val webhookUrl = webhook.getUrl()
    val client = WebhookClient.withUrl(webhookUrl)
    val message = new WebhookMessageBuilder()
      .setUsername(messageAuthor)
      .setContent(messageContent)
      .build()
    client.send(message)
    client.close()
  }

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < onlineRecentDuration
    }
    recentDeaths.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < deathRecentDuration
    }
    recentLevels.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < recentLevelExpiry
    }
    currentOnline.clear()
  }

  private def vocEmoji(char: CharacterResponse): String = {
    val voc = char.characters.character.vocation.toLowerCase.split(' ').last
    voc match {
      case "knight" => ":shield:"
      case "druid" => ":snowflake:"
      case "sorcerer" => ":fire:"
      case "paladin" => ":bow_and_arrow:"
      case "none" => ":hatching_chick:"
      case _ => ""
    }
  }

  private def charUrl(char: String): String =
    s"https://www.tibia.com/community/?name=${char.replaceAll(" ", "+")}"

  private def creatureImageUrl(creature: String): String = {
    val finalCreature = Config.creatureUrlMappings.getOrElse(creature.toLowerCase, {
      // Capitalise the start of each word, including after punctuation e.g. "Mooh'Tah Warrior", "Two-Headed Turtle"
      val rx1 = """([^\w]\w)""".r
      val parsed1 = rx1.replaceAllIn(creature, m => m.group(1).toUpperCase)

      // Lowercase the articles, prepositions etc., e.g. "The Voice of Ruin"
      val rx2 = """( A| Of| The| In| On| To| And| With| From)(?=( ))""".r
      val parsed2 = rx2.replaceAllIn(parsed1, m => m.group(1).toLowerCase)

      // Replace spaces with underscores and make sure the first letter is capitalised
      parsed2.replaceAll(" ", "_").capitalize
    })
    s"https://tibia.fandom.com/wiki/Special:Redirect/file/$finalCreature.gif"
  }

  lazy val stream: RunnableGraph[Cancellable] =
    sourceTick via
      getWorld via
      getCharacterData via
      scanForDeaths via
      postToDiscordAndCleanUp to Sink.ignore

}
