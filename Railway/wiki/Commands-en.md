# Command Reference

Main command: `/rw`

---

## Line Management


| Command                                                        | Description                                |
| -------------------------------------------------------------- | ------------------------------------------ |
| `/rw line create <line_id> <display_name>`                     | Create a new line                          |
| `/rw line delete <line_id> confirm`                            | Delete an existing line                    |
| `/rw line list`                                                | List all lines                             |
| `/rw line rename <line_id> <new_name>`                         | Rename a line                              |
| `/rw line setcolor <line_id> <color_code>`                     | Set line color (e.g. `&9` for blue)        |
| `/rw line setterminus <line_id> <terminus_name>`               | Set terminus description                   |
| `/rw line setmaxspeed <line_id> <speed>`                       | Set maximum speed for the line             |
| `/rw line setprice <line_id> <price>`                          | Set legacy flat ticket price               |
| `/rw line setprice <line_id> flat <base>`                      | Set flat pricing                           |
| `/rw line setprice <line_id> distance <base> <perBlock> [max]` | Set distance-based pricing                 |
| `/rw line setprice <line_id> interval <base> <perStop> [max]`  | Set interval-based pricing                 |
| `/rw line setprice reset <line_id>`                            | Reset to legacy flat pricing               |
| `/rw line priceinfo <line_id>`                                 | View pricing details and active discounts  |
| `/rw line setstatus <normal                                    | suspended                                  |
| `/rw line addstop <line_id> <stop_id> [index]`                 | Add a stop to the line (optional position) |
| `/rw line delstop <line_id> <stop_id>`                         | Remove a stop from the line                |
| `/rw line stops <line_id>`                                     | Show all stops on the line                 |
| `/rw line addportal <line_id> <portal_id>`                     | Allow the line to use a portal             |
| `/rw line delportal <line_id> <portal_id>`                     | Remove a portal from the line              |
| `/rw line portals <line_id>`                                   | Show portals enabled for the line          |
| `/rw line info <line_id>`                                      | Display detailed line info and members     |
| `/rw line recordroute <line_id>`                               | Record or save route points                |
| `/rw line clearroute <line_id> confirm`                        | Clear recorded route points                |
| `/rw line routeinfo <line_id>`                                 | Show route recording status                |
| `/rw line protect <on                                          | off>`                                      |
| `/rw line clonereverse <source_id> <new_id> [suffix]`          | Clone line in reverse direction            |
| `/rw line trust <line_id> <player>`                            | Add a line administrator                   |
| `/rw line untrust <line_id> <player>`                          | Remove a line administrator                |
| `/rw line owner <line_id> <player>`                            | Transfer line ownership                    |


## Stop Management


| Command                                           | Description                           |
| ------------------------------------------------- | ------------------------------------- |
| `/rw stop create <stop_id> <display_name>`        | Create a new stop                     |
| `/rw stop delete <stop_id> confirm`               | Delete a stop and its configuration   |
| `/rw stop list`                                   | List all stops                        |
| `/rw stop rename <stop_id> <new_name>`            | Rename a stop                         |
| `/rw stop info <stop_id>`                         | Show detailed info for a stop         |
| `/rw stop setcorners <stop_id>`                   | Apply the currently selected region   |
| `/rw stop setpoint [stopId] [yaw]`                | Set the StopPoint (powered rail)      |
| `/rw stop addtransfer <stop_id> <line_id>`        | Add a transfer line                   |
| `/rw stop deltransfer <stop_id> <line_id>`        | Remove a transfer line                |
| `/rw stop listtransfers <stop_id>`                | List transfer lines                   |
| `/rw stop settitle <stop_id> <type> <key> <text>` | Set custom title display              |
| `/rw stop deltitle <stop_id> <type> [key]`        | Delete custom title (or specific key) |
| `/rw stop listtitles <stop_id>`                   | List custom title configurations      |
| `/rw stop tp <stop_id>`                           | Teleport to a stop                    |
| `/rw stop trust <stop_id> <player>`               | Add a stop administrator              |
| `/rw stop untrust <stop_id> <player>`             | Remove a stop administrator           |
| `/rw stop owner <stop_id> <player>`               | Transfer stop ownership               |
| `/rw stop link <allow                             | deny> `                               |


## Portal Management


| Command                                       | Description                       |
| --------------------------------------------- | --------------------------------- |
| `/rw portal create <portal_id>`               | Create a minecart portal entrance |
| `/rw portal setdest <portal_id>`              | Set the portal destination        |
| `/rw portal link <portal_id_1> <portal_id_2>` | Link two portals both ways        |
| `/rw portal delete <portal_id> confirm`       | Delete a portal                   |
| `/rw portal list`                             | List all portals                  |
| `/rw portal trust <portal_id> <player>`       | Add a portal administrator        |
| `/rw portal untrust <portal_id> <player>`     | Remove a portal administrator     |
| `/rw portal owner <portal_id> <player>`       | Transfer portal ownership         |
| `/rw portal reload`                           | Reload portal configuration       |


## Train Service Management


| Command                                   | Description                                   |
| ----------------------------------------- | --------------------------------------------- |
| `/rw line setheadway <line_id> <seconds>` | Set headway interval for automatic departures |
| `/rw line setdwell <line_id> <ticks>`     | Set dwell time at each stop (20 ticks = 1s)   |
| `/rw line setcarts <line_id> <count>`     | Set number of minecarts per train (1-32)      |
| `/rw line enableservice <line_id>`        | Enable automatic train service                |
| `/rw line disableservice <line_id>`       | Disable automatic train service               |
| `/rw line serviceinfo <line_id>`          | Show service configuration for a line         |


## System Management


| Command      | Description                        |
| ------------ | ---------------------------------- |
| `/rw gui`    | Open the Railway GUI               |
| `/rw reload` | Reload all plugin configs and data |


