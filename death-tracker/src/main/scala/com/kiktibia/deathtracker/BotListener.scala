package com.kiktibia.deathtracker

import com.kiktibia.deathtracker.BotApp
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class BotListener extends ListenerAdapter {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    event.getName match {
      case "reload" =>
        handleEvent(event)
      case _ =>
    }
  }

  private def handleEvent(event: SlashCommandInteractionEvent): Unit = {
    val embed = BotApp.reload()
		event.replyEmbeds(embed).queue()
  }
}
