# Discord Bot for Tibia

It is currently configured for [Seanera](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/scala/com/kiktibia/deathtracker/tibiadata/TibiaDataClient.scala#L20).    
Current features include an online list and player deaths feed.

### Online player list    

![examples](https://i.imgur.com/S72fiHb.png)

![examples](https://i.imgur.com/AkaTy62.png)

### Death list    
  
  `no color` = neutral pve    
  `white` = neutral pvp    
  `red` = ally    
  `green` = hunted    
  `purple` = rare boss (this pokes)
  
![examples](https://i.imgur.com/09xAyde.gif)

It will poke a [discord role](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L23) if someone dies to a [tracked monster](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L24-L94).

![tracked boss](https://i.imgur.com/cbwovAO.png)

## Pre-requisites:

1. linux host
1. `docker` installed.
1. `default-jre` installed.
1. `sbt` installed.

## Deployment Steps

1. `cd death-tracker`
1. `sbt docker:publishLocal`
1. take note of the docker \<image id\> you just created using `docker images`   

![docker image id](https://i.imgur.com/nXvSeIL.png)

4. Create an `prod.env` file with the discord server/channel id & bot authentication token:
```
TOKEN=XXXXXXXXXXXXXXXXXXXXXX   
GUILD_ID=XXXXXXXXXXXXXXXXXXX   
DEATHS_CHANNEL_ID=XXXXXXXXXXXXXXXXXXX
```
5. Run the docker container you just created while parsing the `prod.env` file:    
`docker run --rm -d --env-file prod.env <image_id>`    
