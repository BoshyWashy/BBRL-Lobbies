# BBRL-Lobbies
Lobby system for the Builders & Boaters Racing League (BBRL) server
Known supported versions: 1.21.1, 1.21.4

# How to install:
- Requires the TimingSystem plugins by FrosthexABG
- Place the latest release .jar file into your plugins folder
- With the plugin installed, use /votetrack add (Exact name of the track, no spaces) (laps) (pits)
- If you dont want players to race during an event, use /votelobby close to disable it, and /votelobby open to enable it again

# Features:
- Voting System for all players to join and vote what tracks they want
- Specific tracks to be raced on listed in config with a set amount of laps and pits and edited in /votetrack add
- Coordinates in config for where the voting area is
- 8 minute maximum timer for each quickrace
- Permission "bbrl.admin" to open, close, the votelobby, and to add and remove tracks from the selection of tracks.

# Commands:
- /votejoin - Accessible to anyone, joins votelobby
- /voteleave - Accessible to anyone, leaves votelobby
- /votelobby <open|close> - Opens/Closes the votelobby, useful for during events
- /votetrack add <trackname> <laps> <pits> - Adds a track to the voting selection
- /votetrack remove <trackname> - Removes a track from the voting selection
