# Death Tracker

Discord bot to track when players are killed on Tibia.    
It is currently configured for [Seanera](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/scala/com/kiktibia/deathtracker/tibiadata/TibiaDataClient.scala#L20).

  `no color` = neutral pve    
  `white` = neutral pvp    
  `red` = ally    
  `green` = hunted    
  `purple` = rare boss (this pokes)    

![examples](https://i.imgur.com/nMJK05h.gif)

It will also poke a [discord role](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L23) if someone dies to a [tracked monster](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L24-L94).

![tracked boss](https://i.imgur.com/Tjofi3h.png)

# Deployment steps

Build docker image  
1. `cd death-tracker`
1. `sbt docker:publishLocal`

Copy to server  
1. `docker images`
1. `docker save <image_id> | bzip2 | ssh bots docker load`

On the server
1. Create an `prod.env` file with the discord server/channel id & bot authentication token:
```
TOKEN=XXXXXXXXXXXXXXXXXXXXXX   
GUILD_ID=XXXXXXXXXXXXXXXXXXX   
DEATHS_CHANNEL_ID=XXXXXXXXXXXXXXXXXXX
```
2. Run the docker container, pointing to the env file created in step 1:    
`docker run --rm -d --env-file prod.env --name death-tracker <image_id>`
