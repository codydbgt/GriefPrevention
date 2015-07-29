/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2011 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;


import org.bukkit.Achievement;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.TravelAgent;
import org.bukkit.BanList.Type;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.util.BlockIterator;

class PlayerEventHandler implements Listener 
{
	private DataStore dataStore;
	
	//list of temporarily banned ip's
	private ArrayList<IpBanInfo> tempBannedIps = new ArrayList<IpBanInfo>();
	
	//number of milliseconds in a day
	private final long MILLISECONDS_IN_DAY = 1000 * 60 * 60 * 24;
	
	//timestamps of login and logout notifications in the last minute
	private ArrayList<Long> recentLoginLogoutNotifications = new ArrayList<Long>();
	
	//regex pattern for the "how do i claim land?" scanner
	private Pattern howToClaimPattern = null;
	
	//typical constructor, yawn
	PlayerEventHandler(DataStore dataStore, GriefPrevention plugin)
	{
		this.dataStore = dataStore;
	}
	
	//when a player chats, monitor for spam
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	synchronized void onPlayerChat (AsyncPlayerChatEvent event)
	{		
		Player player = event.getPlayer();
		if(!player.isOnline())
		{
			event.setCancelled(true);
			return;
		}
		
		String message = event.getMessage();
		
		boolean muted = this.handlePlayerChat(player, message, event);
		Set<Player> recipients = event.getRecipients();
		
		//muted messages go out to only the sender
		if(muted)
		{
		    recipients.clear();
		    recipients.add(player);
		}
		
		//soft muted messages go out to all soft muted players
		else if(this.dataStore.isSoftMuted(player.getUniqueId()))
		{
		    String notificationMessage = "(Muted " + player.getName() + "): " + message;
		    Set<Player> recipientsToKeep = new HashSet<Player>();
		    for(Player recipient : recipients)
		    {
		        if(this.dataStore.isSoftMuted(recipient.getUniqueId()))
		        {
		            recipientsToKeep.add(recipient);
		        }
		        else if(recipient.hasPermission("griefprevention.eavesdrop"))
		        {
		            recipient.sendMessage(ChatColor.GRAY + notificationMessage);
		        }
		    }
		    recipients.clear();
		    recipients.addAll(recipientsToKeep);
		    
		    GriefPrevention.AddLogEntry(notificationMessage, CustomLogEntryTypes.Debug, true);
		}
		
		//remaining messages
		else
		{
		    //enter in abridged chat logs
		    this.makeSocialLogEntry(player.getName(), message);
		    
		    //based on ignore lists, remove some of the audience
		    Set<Player> recipientsToRemove = new HashSet<Player>();
		    PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		    for(Player recipient : recipients)
		    {
		        if(playerData.ignoredPlayers.containsKey(recipient.getUniqueId()))
		        {
		            recipientsToRemove.add(recipient);
		        }
		        else
		        {
		            PlayerData targetPlayerData = this.dataStore.getPlayerData(recipient.getUniqueId());
		            if(targetPlayerData.ignoredPlayers.containsKey(player.getUniqueId()))
		            {
		                recipientsToRemove.add(recipient);
		            }
		        }
		    }
		    
		    recipients.removeAll(recipientsToRemove);
		}
	}
	
	//last chat message shown, regardless of who sent it
	private String lastChatMessage = "";
	private long lastChatMessageTimestamp = 0;
	
	//number of identical messages in a row
	private int duplicateMessageCount = 0;
	
	//returns true if the message should be sent, false if it should be muted 
	private boolean handlePlayerChat(Player player, String message, PlayerEvent event)
	{
		//FEATURE: automatically educate players about claiming land
		//watching for message format how*claim*, and will send a link to the basics video
		if(this.howToClaimPattern == null)
		{
			this.howToClaimPattern = Pattern.compile(this.dataStore.getMessage(Messages.HowToClaimRegex), Pattern.CASE_INSENSITIVE);
		}
		
		if(this.howToClaimPattern.matcher(message).matches())
		{
			if(GriefPrevention.instance.creativeRulesApply(player.getLocation()))
			{
				GriefPrevention.sendMessage(player, TextMode.Info, Messages.CreativeBasicsVideo2, 10L, DataStore.CREATIVE_VIDEO_URL);
			}
			else
			{
				GriefPrevention.sendMessage(player, TextMode.Info, Messages.SurvivalBasicsVideo2, 10L, DataStore.SURVIVAL_VIDEO_URL);
			}
		}
		
		//FEATURE: automatically educate players about the /trapped command
		//check for "trapped" or "stuck" to educate players about the /trapped command
		if(!message.contains("/trapped") && (message.contains("trapped") || message.contains("stuck") || message.contains(this.dataStore.getMessage(Messages.TrappedChatKeyword))))
		{
			GriefPrevention.sendMessage(player, TextMode.Info, Messages.TrappedInstructions, 10L);
		}
		
		//FEATURE: monitor for chat and command spam
		
		if(!GriefPrevention.instance.config_spam_enabled) return false;
		
		//if the player has permission to spam, don't bother even examining the message
		if(player.hasPermission("griefprevention.spam")) return false;
		
		boolean spam = false;
		String mutedReason = null;
		
		//prevent bots from chatting - require movement before talking for any newish players
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		if(playerData.noChatLocation != null)
        {
		    Location currentLocation = player.getLocation();
            if(currentLocation.getBlockX() == playerData.noChatLocation.getBlockX() &&
               currentLocation.getBlockZ() == playerData.noChatLocation.getBlockZ())
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoChatUntilMove, 10L);
                spam = true;
                mutedReason = "pre-movement chat";
            }
            else
            {
                playerData.noChatLocation = null;
            }
        }
		
		//remedy any CAPS SPAM, exception for very short messages which could be emoticons like =D or XD
		if(message.length() > 4 && this.stringsAreSimilar(message.toUpperCase(), message))
		{
			//exception for strings containing forward slash to avoid changing a case-sensitive URL
			if(event instanceof AsyncPlayerChatEvent)
			{
				((AsyncPlayerChatEvent)event).setMessage(message.toLowerCase());
			}
		}
		
		//always mute an exact match to the last chat message
		long now = new Date().getTime();
		if(mutedReason != null && message.equals(this.lastChatMessage) && now - this.lastChatMessageTimestamp < 750)
		{
		    playerData.spamCount += ++this.duplicateMessageCount;
		    spam = true;
		    mutedReason = "repeat message";
		}
		else
		{
		    this.lastChatMessage = message;
		    this.lastChatMessageTimestamp = now;
		    this.duplicateMessageCount = 0;
		}
		
		//where other types of spam are concerned, casing isn't significant
		message = message.toLowerCase();
		
		//check message content and timing		
		long millisecondsSinceLastMessage = now - playerData.lastMessageTimestamp.getTime();
		
		//if the message came too close to the last one
		if(millisecondsSinceLastMessage < 1500)
		{
			//increment the spam counter
			playerData.spamCount++;
			spam = true;
		}
		
		//if it's very similar to the last message from the same player and within 10 seconds of that message
		if(mutedReason == null && this.stringsAreSimilar(message, playerData.lastMessage) && now - playerData.lastMessageTimestamp.getTime() < 10000)
		{
			playerData.spamCount++;
			spam = true;
			mutedReason = "similar message";
		}
		
		//filter IP addresses
		if(mutedReason == null)
		{
			if(GriefPrevention.instance.containsBlockedIP(message))
			{
				//spam notation
				playerData.spamCount+=1;
				spam = true;
				
				//block message
				mutedReason = "IP address";
			}
		}
		
		//if the message was mostly non-alpha-numerics or doesn't include much whitespace, consider it a spam (probably ansi art or random text gibberish) 
		if(mutedReason == null && message.length() > 5)
		{
			int symbolsCount = 0;
			int whitespaceCount = 0;
			for(int i = 0; i < message.length(); i++)
			{
				char character = message.charAt(i);
				if(!(Character.isLetterOrDigit(character)))
				{
					symbolsCount++;
				}
				
				if(Character.isWhitespace(character))
				{
					whitespaceCount++;
				}
			}
			
			if(symbolsCount > message.length() / 2 || (message.length() > 15 && whitespaceCount < message.length() / 10))
			{
				spam = true;
				if(playerData.spamCount > 0) mutedReason = "gibberish";
				playerData.spamCount++;
			}
		}
		
		//very short messages close together are spam
		if(mutedReason == null && message.length() < 5 && millisecondsSinceLastMessage < 3000)
		{
			spam = true;
			playerData.spamCount++;
		}
		
		//if the message was determined to be a spam, consider taking action		
		if(spam)
		{		
			//anything above level 8 for a player which has received a warning...  kick or if enabled, ban 
			if(playerData.spamCount > 8 && playerData.spamWarned)
			{
				if(GriefPrevention.instance.config_spam_banOffenders)
				{
					//log entry
					GriefPrevention.AddLogEntry("Banning " + player.getName() + " for spam.", CustomLogEntryTypes.AdminActivity);
					
					//kick and ban
					PlayerKickBanTask task = new PlayerKickBanTask(player, GriefPrevention.instance.config_spam_banMessage, "GriefPrevention Anti-Spam",true);
					GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 1L);
				}
				else
				{
					//log entry
					GriefPrevention.AddLogEntry("Kicking " + player.getName() + " for spam.", CustomLogEntryTypes.AdminActivity);
					
					//just kick
					PlayerKickBanTask task = new PlayerKickBanTask(player, "", "GriefPrevention Anti-Spam", false);
					GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 1L);					
				}
				
				return true;
			}
			
			//cancel any messages while at or above the third spam level and issue warnings
			//anything above level 2, mute and warn
			if(playerData.spamCount >= 4)
			{
				if(mutedReason == null)
				{
				    mutedReason = "too-frequent text";
				}
				if(!playerData.spamWarned)
				{
					GriefPrevention.sendMessage(player, TextMode.Warn, GriefPrevention.instance.config_spam_warningMessage, 10L);
					GriefPrevention.AddLogEntry("Warned " + player.getName() + " about spam penalties.", CustomLogEntryTypes.Debug, true);
					playerData.spamWarned = true;
				}
			}
			
			if(mutedReason != null)
			{
				//make a log entry
				GriefPrevention.AddLogEntry("Muted " + mutedReason + ".");
				GriefPrevention.AddLogEntry("Muted " + player.getName() + " " + mutedReason + ":" + message, CustomLogEntryTypes.Debug, true);
				
				//cancelling the event guarantees other players don't receive the message
				return true;
			}		
		}
		
		//otherwise if not a spam, reset the spam counter for this player
		else
		{
			playerData.spamCount = 0;
			playerData.spamWarned = false;
		}
		
		//in any case, record the timestamp of this message and also its content for next time
		playerData.lastMessageTimestamp = new Date();
		playerData.lastMessage = message;
		
		return false;
	}
	
	//if two strings are 75% identical, they're too close to follow each other in the chat
	private boolean stringsAreSimilar(String message, String lastMessage)
	{
	    //determine which is shorter
		String shorterString, longerString;
		if(lastMessage.length() < message.length())
		{
			shorterString = lastMessage;
			longerString = message;
		}
		else
		{
			shorterString = message;
			longerString = lastMessage;
		}
		
		if(shorterString.length() <= 5) return shorterString.equals(longerString);
		
		//set similarity tolerance
		int maxIdenticalCharacters = longerString.length() - longerString.length() / 4;
		
		//trivial check on length
		if(shorterString.length() < maxIdenticalCharacters) return false;
		
		//compare forward
		int identicalCount = 0;
		int i;
		for(i = 0; i < shorterString.length(); i++)
		{
			if(shorterString.charAt(i) == longerString.charAt(i)) identicalCount++;
			if(identicalCount > maxIdenticalCharacters) return true;
		}
		
		//compare backward
		int j;
		for(j = 0; j < shorterString.length() - i; j++)
		{
			if(shorterString.charAt(shorterString.length() - j - 1) == longerString.charAt(longerString.length() - j - 1)) identicalCount++;
			if(identicalCount > maxIdenticalCharacters) return true;
		}
		
		return false;
	}

	//when a player uses a slash command...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	synchronized void onPlayerCommandPreprocess (PlayerCommandPreprocessEvent event)
	{
		String message = event.getMessage();
	    String [] args = message.split(" ");
		
		String command = args[0].toLowerCase();
		
		Player player = event.getPlayer();
		PlayerData playerData = null;
		
		//if a whisper
		if(GriefPrevention.instance.config_eavesdrop_whisperCommands.contains(command) && args.length > 1)
		{
		    //determine target player, might be NULL
            Player targetPlayer = GriefPrevention.instance.getServer().getPlayer(args[1]);
		    
		    //if eavesdrop enabled and sender doesn't have the eavesdrop permission, eavesdrop
		    if(GriefPrevention.instance.config_whisperNotifications && !player.hasPermission("griefprevention.eavesdrop"))
    		{			
                //except for when the recipient has eavesdrop permission
                if(targetPlayer == null || targetPlayer.hasPermission("griefprevention.eavesdrop"))
                {
                    StringBuilder logMessageBuilder = new StringBuilder();
        			logMessageBuilder.append("[[").append(event.getPlayer().getName()).append("]] ");
        			
        			for(int i = 1; i < args.length; i++)
        			{
        				logMessageBuilder.append(args[i]).append(" ");
        			}
        			
        			String logMessage = logMessageBuilder.toString();
        			
        			Collection<Player> players = (Collection<Player>)GriefPrevention.instance.getServer().getOnlinePlayers();
        			for(Player onlinePlayer : players)
        			{
        				if(onlinePlayer.hasPermission("griefprevention.eavesdrop") && !onlinePlayer.equals(targetPlayer))
        				{
        				    onlinePlayer.sendMessage(ChatColor.GRAY + logMessage);
        				}
        			}
                }
    		}
            
            //ignore feature
            if(targetPlayer != null && targetPlayer.isOnline())
            {
                //if either is ignoring the other, cancel this command
                playerData = this.dataStore.getPlayerData(player.getUniqueId());
                if(playerData.ignoredPlayers.containsKey(targetPlayer.getUniqueId()))
                {
                    event.setCancelled(true);
                    return;
                }
                
                PlayerData targetPlayerData = this.dataStore.getPlayerData(targetPlayer.getUniqueId());
                if(targetPlayerData.ignoredPlayers.containsKey(player.getUniqueId()))
                {
                    event.setCancelled(true);
                    return;
                }
            }
		}
		
		//if in pvp, block any pvp-banned slash commands
		if(playerData == null) playerData = this.dataStore.getPlayerData(event.getPlayer().getUniqueId());

		if((playerData.inPvpCombat() ) && GriefPrevention.instance.config_pvp_blockedCommands.contains(command))
		{
			event.setCancelled(true);
			GriefPrevention.sendMessage(event.getPlayer(), TextMode.Err, Messages.CommandBannedInPvP);
			return;
		}
		
		//if the slash command used is in the list of monitored commands, treat it like a chat message (see above)
		boolean isMonitoredCommand = false;
		for(String monitoredCommand : GriefPrevention.instance.config_spam_monitorSlashCommands)
		{
			if(args[0].equalsIgnoreCase(monitoredCommand))
			{
				isMonitoredCommand = true;
				break;
			}
		}
		
		if(isMonitoredCommand)
		{
		    //if anti spam enabled, check for spam
	        if(GriefPrevention.instance.config_spam_enabled)
		    {
		        event.setCancelled(this.handlePlayerChat(event.getPlayer(), event.getMessage(), event));
		    }
		    
		    //unless cancelled, log in abridged logs
	        if(!event.isCancelled())
		    {
		        StringBuilder builder = new StringBuilder();
		        for(String arg : args)
		        {
		            builder.append(arg + " ");
		        }
		        
	            this.makeSocialLogEntry(event.getPlayer().getName(), builder.toString());
		    }
		}
		
		//if requires access trust, check for permission
		isMonitoredCommand = false;
		String lowerCaseMessage = message.toLowerCase();
		for(String monitoredCommand : GriefPrevention.instance.config_claims_commandsRequiringAccessTrust)
        {
            if(lowerCaseMessage.startsWith(monitoredCommand))
            {
                isMonitoredCommand = true;
                break;
            }
        }
        
        if(isMonitoredCommand)
        {
            Claim claim = this.dataStore.getClaimAt(player.getLocation(), false, playerData.lastClaim);
            if(claim != null)
            {
                playerData.lastClaim = claim;
                String reason = claim.allowAccess(player); 
                if(reason != null)
                {
                    GriefPrevention.sendMessage(player, TextMode.Err, reason);
                    event.setCancelled(true);
                }
            }
        }
	}
	
	static int longestNameLength = 10;
	static void makeSocialLogEntry(String name, String message)
	{
        StringBuilder entryBuilder = new StringBuilder(name);
        for(int i = name.length(); i < longestNameLength; i++)
        {
            entryBuilder.append(' ');
        }
        entryBuilder.append(": " + message);
        
        longestNameLength = Math.max(longestNameLength, name.length());
        
        GriefPrevention.AddLogEntry(entryBuilder.toString(), CustomLogEntryTypes.SocialActivity, true);
    }

    private ConcurrentHashMap<UUID, Date> lastLoginThisServerSessionMap = new ConcurrentHashMap<UUID, Date>();

    //counts how many players are using each IP address connected to the server right now
    private ConcurrentHashMap<String, Integer> ipCountHash = new ConcurrentHashMap<String, Integer>();
	
	//when a player attempts to join the server...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerLogin (PlayerLoginEvent event)
	{
		Player player = event.getPlayer();
		
		//all this is anti-spam code
		if(GriefPrevention.instance.config_spam_enabled)
		{
			//FEATURE: login cooldown to prevent login/logout spam with custom clients
		    long now = Calendar.getInstance().getTimeInMillis();
		    
			//if allowed to join and login cooldown enabled
			if(GriefPrevention.instance.config_spam_loginCooldownSeconds > 0 && event.getResult() == Result.ALLOWED && !player.hasPermission("griefprevention.spam"))
			{
				//determine how long since last login and cooldown remaining
				Date lastLoginThisSession = lastLoginThisServerSessionMap.get(player.getUniqueId());
				if(lastLoginThisSession != null)
				{
    			    long millisecondsSinceLastLogin = now - lastLoginThisSession.getTime();
    				long secondsSinceLastLogin = millisecondsSinceLastLogin / 1000;
    				long cooldownRemaining = GriefPrevention.instance.config_spam_loginCooldownSeconds - secondsSinceLastLogin;
    				
    				//if cooldown remaining
    				if(cooldownRemaining > 0)
    				{
    					//DAS BOOT!
    					event.setResult(Result.KICK_OTHER);				
    					event.setKickMessage("You must wait " + cooldownRemaining + " seconds before logging-in again.");
    					event.disallow(event.getResult(), event.getKickMessage());
    					return;
    				}
				}
			}
			
			//if logging-in account is banned, remember IP address for later
			if(GriefPrevention.instance.config_smartBan && event.getResult() == Result.KICK_BANNED)
			{
				this.tempBannedIps.add(new IpBanInfo(event.getAddress(), now + this.MILLISECONDS_IN_DAY, player.getName()));
			}
		}
		
		//remember the player's ip address
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		playerData.ipAddress = event.getAddress();
	}
	
	//when a player successfully joins the server...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	void onPlayerJoin(PlayerJoinEvent event)
	{
		Player player = event.getPlayer();
		UUID playerID = player.getUniqueId();
		
		//note login time
		Date nowDate = new Date();
        long now = nowDate.getTime();
		PlayerData playerData = this.dataStore.getPlayerData(playerID);
		playerData.lastSpawn = now;
		playerData.setLastLogin(nowDate);
		this.lastLoginThisServerSessionMap.put(playerID, nowDate);
		
		//if newish, prevent chat until he's moved a bit to prove he's not a bot
		if(!player.hasAchievement(Achievement.MINE_WOOD))
		{
		    playerData.noChatLocation = player.getLocation();
		}
		
		//if player has never played on the server before...
		if(!player.hasPlayedBefore())
		{
			//may need pvp protection
		    GriefPrevention.instance.checkPvpProtectionNeeded(player);
		    
		    //if in survival claims mode, send a message about the claim basics video (except for admins - assumed experts)
		    if(GriefPrevention.instance.config_claims_worldModes.get(player.getWorld()) == ClaimsMode.Survival && !player.hasPermission("griefprevention.adminclaims") && this.dataStore.claims.size() > 10)
		    {
		        WelcomeTask task = new WelcomeTask(player);
		        Bukkit.getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 600L);  //30 seconds after join
		    }
		}
		
		//silence notifications when they're coming too fast
		if(event.getJoinMessage() != null && this.shouldSilenceNotification())
		{
			event.setJoinMessage(null);
		}
		
		//FEATURE: auto-ban accounts who use an IP address which was very recently used by another banned account
		if(GriefPrevention.instance.config_smartBan && !player.hasPlayedBefore())
		{		
			//search temporarily banned IP addresses for this one
			for(int i = 0; i < this.tempBannedIps.size(); i++)
			{
				IpBanInfo info = this.tempBannedIps.get(i);
				String address = info.address.toString();
				
				//eliminate any expired entries
				if(now > info.expirationTimestamp)
				{
					this.tempBannedIps.remove(i--);
				}
				
				//if we find a match				
				else if(address.equals(playerData.ipAddress.toString()))
				{
					//if the account associated with the IP ban has been pardoned, remove all ip bans for that ip and we're done
					OfflinePlayer bannedPlayer = GriefPrevention.instance.getServer().getOfflinePlayer(info.bannedAccountName);
					if(!bannedPlayer.isBanned())
					{
						for(int j = 0; j < this.tempBannedIps.size(); j++)
						{
							IpBanInfo info2 = this.tempBannedIps.get(j);
							if(info2.address.toString().equals(address))
							{
								OfflinePlayer bannedAccount = GriefPrevention.instance.getServer().getOfflinePlayer(info2.bannedAccountName);
								bannedAccount.setBanned(false);
								this.tempBannedIps.remove(j--);
							}
						}
						
						break;
					}
					
					//otherwise if that account is still banned, ban this account, too
					else
					{
						GriefPrevention.AddLogEntry("Auto-banned " + player.getName() + " because that account is using an IP address very recently used by banned player " + info.bannedAccountName + " (" + info.address.toString() + ").", CustomLogEntryTypes.AdminActivity);
						
						//notify any online ops
						Collection<Player> players = (Collection<Player>)GriefPrevention.instance.getServer().getOnlinePlayers();
						for(Player otherPlayer : players)
						{
							if(otherPlayer.isOp())
							{
								GriefPrevention.sendMessage(otherPlayer, TextMode.Success, Messages.AutoBanNotify, player.getName(), info.bannedAccountName);
							}
						}
						
						//ban player
						PlayerKickBanTask task = new PlayerKickBanTask(player, "", "GriefPrevention Smart Ban - Shared Login:" + info.bannedAccountName, true);
						GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 10L);
						
						//silence join message
						event.setJoinMessage("");
						
						break;
					}
				}
			}
		}
		
		//in case player has changed his name, on successful login, update UUID > Name mapping
		GriefPrevention.cacheUUIDNamePair(player.getUniqueId(), player.getName());
		
		//ensure we're not over the limit for this IP address
        InetAddress ipAddress = playerData.ipAddress;
        if(ipAddress != null)
        {
            String ipAddressString = ipAddress.toString();
            int ipLimit = GriefPrevention.instance.config_ipLimit;
            if(ipLimit > 0 && !player.hasAchievement(Achievement.MINE_WOOD))
            {
                Integer ipCount = this.ipCountHash.get(ipAddressString);
                if(ipCount == null) ipCount = 0;
                if(ipCount >= ipLimit)
                {
                    //kick player
                    PlayerKickBanTask task = new PlayerKickBanTask(player, "Sorry, there are too many players logged in with your IP address.", "GriefPrevention IP-sharing limit.", false);
                    GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 10L);
                    
                    //silence join message
                    event.setJoinMessage("");               
                    return;
                }
                else
                {
                    this.ipCountHash.put(ipAddressString, ipCount + 1);
                }
            }
        }
        
        //create a thread to load ignore information
        new IgnoreLoaderThread(playerID, playerData.ignoredPlayers).start();
	}
	
	//when a player spawns, conditionally apply temporary pvp protection 
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    void onPlayerRespawn (PlayerRespawnEvent event)
    {
        Player player = event.getPlayer();
        PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId());
        playerData.lastSpawn = Calendar.getInstance().getTimeInMillis();
        playerData.lastPvpTimestamp = 0;  //no longer in pvp combat
        
        //also send him any messaged from grief prevention he would have received while dead
        if(playerData.messageOnRespawn != null)
        {
            GriefPrevention.sendMessage(player, ChatColor.RESET /*color is alrady embedded in message in this case*/, playerData.messageOnRespawn, 40L);
            playerData.messageOnRespawn = null;
        }
        
        GriefPrevention.instance.checkPvpProtectionNeeded(player);
    }
	
	//when a player dies...
	@EventHandler(priority = EventPriority.LOWEST)
	void onPlayerDeath(PlayerDeathEvent event)
	{
		//FEATURE: prevent death message spam by implementing a "cooldown period" for death messages
		PlayerData playerData = this.dataStore.getPlayerData(event.getEntity().getUniqueId());
		long now = Calendar.getInstance().getTimeInMillis(); 
		if(now - playerData.lastDeathTimeStamp < GriefPrevention.instance.config_spam_deathMessageCooldownSeconds * 1000)
		{
			event.setDeathMessage("");
		}
		
		playerData.lastDeathTimeStamp = now;
		
		//these are related to locking dropped items on death to prevent theft
		playerData.dropsAreUnlocked = false;
		playerData.receivedDropUnlockAdvertisement = false;
	}
	
	//when a player gets kicked...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerKicked(PlayerKickEvent event)
    {
	    Player player = event.getPlayer();
	    PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
	    playerData.wasKicked = true;
    }
	
	//when a player quits...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerQuit(PlayerQuitEvent event)
	{
	    Player player = event.getPlayer();
		UUID playerID = player.getUniqueId();
	    PlayerData playerData = this.dataStore.getPlayerData(playerID);
		boolean isBanned;
		if(playerData.wasKicked)
		{
		    isBanned = player.isBanned();
		}
		else
		{
		    isBanned = false;
		}
		
		//if banned, add IP to the temporary IP ban list
		if(isBanned && playerData.ipAddress != null)
		{
			long now = Calendar.getInstance().getTimeInMillis(); 
			this.tempBannedIps.add(new IpBanInfo(playerData.ipAddress, now + this.MILLISECONDS_IN_DAY, player.getName()));
		}
		
		//silence notifications when they're coming too fast
		if(event.getQuitMessage() != null && this.shouldSilenceNotification())
		{
			event.setQuitMessage(null);
		}
		
		//silence notifications when the player is banned
		if(isBanned)
		{
		    event.setQuitMessage(null);
		}
		
		//make sure his data is all saved - he might have accrued some claim blocks while playing that were not saved immediately
		else
		{
		    this.dataStore.savePlayerData(player.getUniqueId(), playerData);
		}
		
		//FEATURE: players in pvp combat when they log out will die
        if(GriefPrevention.instance.config_pvp_punishLogout && playerData.inPvpCombat())
        {
            player.setHealth(0);
        }
        
        //FEATURE: during a siege, any player who logs out dies and forfeits the siege
        

        
        //drop data about this player
        this.dataStore.clearCachedPlayerData(playerID);
        
        //reduce count of players with that player's IP address
        if(GriefPrevention.instance.config_ipLimit > 0 && !player.hasAchievement(Achievement.MINE_WOOD))
        {
            InetAddress ipAddress = playerData.ipAddress;
            if(ipAddress != null)
            {
                String ipAddressString = ipAddress.toString();
                Integer count = this.ipCountHash.get(ipAddressString);
                if(count == null) count = 1;
                this.ipCountHash.put(ipAddressString, count - 1);
            }
        }
	}
	
	//determines whether or not a login or logout notification should be silenced, depending on how many there have been in the last minute
	private boolean shouldSilenceNotification()
	{
		final long ONE_MINUTE = 60000;
		final int MAX_ALLOWED = 20;
		Long now = Calendar.getInstance().getTimeInMillis();
		
		//eliminate any expired entries (longer than a minute ago)
		for(int i = 0; i < this.recentLoginLogoutNotifications.size(); i++)
		{
			Long notificationTimestamp = this.recentLoginLogoutNotifications.get(i);
			if(now - notificationTimestamp > ONE_MINUTE)
			{
				this.recentLoginLogoutNotifications.remove(i--);
			}
			else
			{
				break;
			}
		}
		
		//add the new entry
		this.recentLoginLogoutNotifications.add(now);
		
		return this.recentLoginLogoutNotifications.size() > MAX_ALLOWED;
	}

	//when a player drops an item
	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerDropItem(PlayerDropItemEvent event)
	{
		Player player = event.getPlayer();
		
		//in creative worlds, dropping items is blocked
		if(GriefPrevention.instance.creativeRulesApply(player.getLocation()))
		{
			event.setCancelled(true);
			return;
		}
		
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		
		//FEATURE: players under siege or in PvP combat, can't throw items on the ground to hide 
		//them or give them away to other players before they are defeated
		
		//if in combat, don't let him drop it
		if(!GriefPrevention.instance.config_pvp_allowCombatItemDrop && playerData.inPvpCombat())
		{
			GriefPrevention.sendMessage(player, TextMode.Err, Messages.PvPNoDrop);
			event.setCancelled(true);			
		}

	}
	
	//when a player teleports via a portal
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
	void onPlayerPortal(PlayerPortalEvent event) 
	{
	    //if the player isn't going anywhere, take no action
	    if(event.getTo() == null || event.getTo().getWorld() == null) return;
	    
	    //don't track in worlds where claims are not enabled
        if(!GriefPrevention.instance.claimsEnabledForWorld(event.getTo().getWorld())) return;
	    
	    Player player = event.getPlayer();
	    
        if(event.getCause() == TeleportCause.NETHER_PORTAL)
        {
            //FEATURE: when players get trapped in a nether portal, send them back through to the other side
            CheckForPortalTrapTask task = new CheckForPortalTrapTask(player, event.getFrom());
            GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 200L);
        
            //FEATURE: if the player teleporting doesn't have permission to build a nether portal and none already exists at the destination, cancel the teleportation
            if(GriefPrevention.instance.config_claims_portalsRequirePermission)
            {
                Location destination = event.getTo();
                if(event.useTravelAgent())
                {
                    if(event.getPortalTravelAgent().getCanCreatePortal())
                    {
                        //hypothetically find where the portal would be created if it were
                        TravelAgent agent = event.getPortalTravelAgent();
                        agent.setCanCreatePortal(false);
                        destination = agent.findOrCreate(destination);
                        agent.setCanCreatePortal(true);
                    }
                    else
                    {
                        //if not able to create a portal, we don't have to do anything here
                        return;
                    }
                }
            
                //if creating a new portal
                if(destination.getBlock().getType() != Material.PORTAL)
                {
                    //check for a land claim and the player's permission that land claim
                    Claim claim = this.dataStore.getClaimAt(destination, false, null);
                    if(claim != null && claim.allowBuild(player, Material.PORTAL) != null)
                    {
                        //cancel and inform about the reason
                        event.setCancelled(true);
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoBuildPortalPermission, claim.getOwnerName());
                    }
                }
            }
        }
	}
	
	//when a player teleports
	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerTeleport(PlayerTeleportEvent event)
	{
	    Player player = event.getPlayer();
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		
		//FEATURE: prevent players from using ender pearls to gain access to secured claims
		if(event.getCause() == TeleportCause.ENDER_PEARL && GriefPrevention.instance.config_claims_enderPearlsRequireAccessTrust)
		{
			Claim toClaim = this.dataStore.getClaimAt(event.getTo(), false, playerData.lastClaim);
			if(toClaim != null)
			{
				playerData.lastClaim = toClaim;
				String noAccessReason = toClaim.allowAccess(player);
				if(noAccessReason != null)
				{
					GriefPrevention.sendMessage(player, TextMode.Err, noAccessReason);
					event.setCancelled(true);
					player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
				}
			}
		}
		
		//FEATURE: prevent teleport abuse to win sieges
		
		//these rules only apply to siege worlds only
		if(!GriefPrevention.instance.config_siege_enabledWorlds.contains(player.getWorld())) return;
		
		Location source = event.getFrom();
		Claim sourceClaim = this.dataStore.getClaimAt(source, false, playerData.lastClaim);
		if(sourceClaim != null)
		{
			GriefPrevention.sendMessage(player, TextMode.Err, Messages.SiegeNoTeleport);
			event.setCancelled(true);
			return;
		}
		
		Location destination = event.getTo();
		Claim destinationClaim = this.dataStore.getClaimAt(destination, false, null);
		if(destinationClaim != null)
		{
			GriefPrevention.sendMessage(player, TextMode.Err, Messages.BesiegedNoTeleport);
			event.setCancelled(true);
			return;
		}
	}
    
	//when a player interacts with an entity...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
	{
		Player player = event.getPlayer();
		Entity entity = event.getRightClicked();
		
		if(!GriefPrevention.instance.claimsEnabledForWorld(entity.getWorld())) return;
		
		//allow horse protection to be overridden to allow management from other plugins
        if (!GriefPrevention.instance.config_claims_protectHorses && entity instanceof Horse) return;
        
        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        
		//if entity is tameable and has an owner, apply special rules
        if(entity instanceof Tameable)
        {
            Tameable tameable = (Tameable)entity;
            if(tameable.isTamed() && tameable.getOwner() != null)
            {
               UUID ownerID = tameable.getOwner().getUniqueId();
               
               //if the player interacting is the owner or an admin in ignore claims mode, always allow
               if(player.getUniqueId().equals(ownerID) || playerData.ignoreClaims)
               {
                   //if giving away pet, do that instead
                   if(playerData.petGiveawayRecipient != null)
                   {
                       tameable.setOwner(playerData.petGiveawayRecipient);
                       playerData.petGiveawayRecipient = null;
                       GriefPrevention.sendMessage(player, TextMode.Success, Messages.PetGiveawayConfirmation);
                       event.setCancelled(true);
                   }
                   
                   return;
               }
               if(!GriefPrevention.instance.pvpRulesApply(entity.getLocation().getWorld()))
               {
                   //otherwise disallow
                   OfflinePlayer owner = GriefPrevention.instance.getServer().getOfflinePlayer(ownerID); 
                   String ownerName = owner.getName();
                   if(ownerName == null) ownerName = "someone";
                   String message = GriefPrevention.instance.dataStore.getMessage(Messages.NotYourPet, ownerName);
                   if(player.hasPermission("griefprevention.ignoreclaims"))
                       message += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                   GriefPrevention.sendMessage(player, TextMode.Err, message);
                   event.setCancelled(true);
                   return;
               }
            }
        }
        
		
		//always allow interactions when player is in ignore claims mode
        if(playerData.ignoreClaims) return;
        
		//if the entity is a vehicle and we're preventing theft in claims		
		if(GriefPrevention.instance.config_claims_preventTheft && entity instanceof Vehicle)
		{
			//if the entity is in a claim
			Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
			if(claim != null)
			{
				//for storage entities, apply container rules (this is a potential theft)
				if(entity instanceof InventoryHolder)
				{					
					String noContainersReason = claim.allowContainers(player);
					if(noContainersReason != null)
					{
						GriefPrevention.sendMessage(player, TextMode.Err, noContainersReason);
						event.setCancelled(true);
						return;
					}
				}
				
				//for boats, apply access rules
				else if(entity instanceof Boat)
				{
					String noAccessReason = claim.allowAccess(player);
					if(noAccessReason != null)
					{
						player.sendMessage(noAccessReason);
						event.setCancelled(true);
						return;
					}
				}
			}
		}
		
		//if the entity is an animal, apply container rules
        if((GriefPrevention.instance.config_claims_preventTheft && entity instanceof Animals) || (entity.getType() == EntityType.VILLAGER && GriefPrevention.instance.config_claims_villagerTradingRequiresTrust))
        {
            //if the entity is in a claim
            Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
            if(claim != null)
            {
                if(claim.allowContainers(player) != null)
                {
                    String message = GriefPrevention.instance.dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
                    if(player.hasPermission("griefprevention.ignoreclaims"))
                        message += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                    GriefPrevention.sendMessage(player, TextMode.Err, message);
                    event.setCancelled(true);
                    return;
                }
            }
        }
		
		//if preventing theft, prevent leashing claimed creatures
		if(GriefPrevention.instance.config_claims_preventTheft && entity instanceof Creature && player.getItemInHand().getType() == Material.LEASH)
		{
		    Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
            if(claim != null)
            {
                String failureReason = claim.allowContainers(player);
                if(failureReason != null)
                {
                    event.setCancelled(true);
                    GriefPrevention.sendMessage(player, TextMode.Err, failureReason);
                    return;                    
                }
            }
		}
	}
	
	//when a player picks up an item...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerPickupItem(PlayerPickupItemEvent event)
	{
		Player player = event.getPlayer();

		//FEATURE: lock dropped items to player who dropped them
		
		//who owns this stack?
		Item item = event.getItem();
		List<MetadataValue> data = item.getMetadata("GP_ITEMOWNER");
		if(data != null && data.size() > 0)
		{
		    UUID ownerID = (UUID)data.get(0).value();
		    
		    //has that player unlocked his drops?
		    OfflinePlayer owner = GriefPrevention.instance.getServer().getOfflinePlayer(ownerID);
		    String ownerName = GriefPrevention.lookupPlayerName(ownerID);
		    if(owner.isOnline() && !player.equals(owner))
		    {
		        PlayerData playerData = this.dataStore.getPlayerData(ownerID);

                //if locked, don't allow pickup
		        if(!playerData.dropsAreUnlocked)
		        {
		            event.setCancelled(true);
		            
		            //if hasn't been instructed how to unlock, send explanatory messages
		            if(!playerData.receivedDropUnlockAdvertisement)
		            {
		                GriefPrevention.sendMessage(owner.getPlayer(), TextMode.Instr, Messages.DropUnlockAdvertisement);
		                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PickupBlockedExplanation, ownerName);
		                playerData.receivedDropUnlockAdvertisement = true;
		            }
		            
		            return;
		        }
		    }
		}
		
		//the rest of this code is specific to pvp worlds
		if(!GriefPrevention.instance.pvpRulesApply(player.getWorld())) return;
		
		//if we're preventing spawn camping and the player was previously empty handed...
		if(GriefPrevention.instance.config_pvp_protectFreshSpawns && (player.getItemInHand().getType() == Material.AIR))
		{
			//if that player is currently immune to pvp
			PlayerData playerData = this.dataStore.getPlayerData(event.getPlayer().getUniqueId());
			if(playerData.pvpImmune)
			{
				//if it's been less than 10 seconds since the last time he spawned, don't pick up the item
				long now = Calendar.getInstance().getTimeInMillis();
				long elapsedSinceLastSpawn = now - playerData.lastSpawn;
				if(elapsedSinceLastSpawn < 10000)
				{
					event.setCancelled(true);
					return;
				}
				
				//otherwise take away his immunity. he may be armed now.  at least, he's worth killing for some loot
				playerData.pvpImmune = false;
				GriefPrevention.sendMessage(player, TextMode.Warn, Messages.PvPImmunityEnd);
			}			
		}
	}
	
	//when a player switches in-hand items
	@EventHandler(ignoreCancelled = true)
	public void onItemHeldChange(PlayerItemHeldEvent event)
	{
		Player player = event.getPlayer();
		
		//if he's switching to the golden shovel
		int newSlot = event.getNewSlot();
		ItemStack newItemStack = player.getInventory().getItem(newSlot);
		if(newItemStack != null && newItemStack.getType() == GriefPrevention.instance.config_claims_modificationTool)
		{
			//give the player his available claim blocks count and claiming instructions, but only if he keeps the shovel equipped for a minimum time, to avoid mouse wheel spam
			if(GriefPrevention.instance.claimsEnabledForWorld(player.getWorld()))
			{
				EquipShovelProcessingTask task = new EquipShovelProcessingTask(player);
				GriefPrevention.instance.getServer().getScheduler().scheduleSyncDelayedTask(GriefPrevention.instance, task, 15L);  //15L is approx. 3/4 of a second
			}
		}
	}
	
	//block use of buckets within other players' claims
	private HashSet<Material> commonAdjacentBlocks_water = new HashSet<Material>(Arrays.asList(Material.WATER, Material.STATIONARY_WATER, Material.SOIL, Material.DIRT, Material.STONE));
	private HashSet<Material> commonAdjacentBlocks_lava = new HashSet<Material>(Arrays.asList(Material.LAVA, Material.STATIONARY_LAVA, Material.DIRT, Material.STONE));
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerBucketEmpty (PlayerBucketEmptyEvent bucketEvent)
	{
		if(!GriefPrevention.instance.claimsEnabledForWorld(bucketEvent.getBlockClicked().getWorld())) return;
	    
	    Player player = bucketEvent.getPlayer();
		Block block = bucketEvent.getBlockClicked().getRelative(bucketEvent.getBlockFace());
		int minLavaDistance = 10;
		
		//make sure the player is allowed to build at the location
		String noBuildReason = GriefPrevention.instance.allowBuild(player, block.getLocation(), Material.WATER);
		if(noBuildReason != null)
		{
			GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason);
			bucketEvent.setCancelled(true);
			return;
		}
		
		//if the bucket is being used in a claim, allow for dumping lava closer to other players
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, playerData.lastClaim);
		if(claim != null)
		{
			minLavaDistance = 3;
		}
		
		//otherwise no wilderness dumping in creative mode worlds
		else if(GriefPrevention.instance.creativeRulesApply(block.getLocation()))
		{
			if(block.getY() >= GriefPrevention.instance.getSeaLevel(block.getWorld()) - 5 && !player.hasPermission("griefprevention.lava"))
			{
				if(bucketEvent.getBucket() == Material.LAVA_BUCKET)
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoWildernessBuckets);
					bucketEvent.setCancelled(true);
					return;
				}
			}
		}
		
		//lava buckets can't be dumped near other players unless pvp is on
		if(!GriefPrevention.instance.pvpRulesApply(block.getWorld()) && !player.hasPermission("griefprevention.lava"))
		{
			if(bucketEvent.getBucket() == Material.LAVA_BUCKET)
			{
				List<Player> players = block.getWorld().getPlayers();
				for(int i = 0; i < players.size(); i++)
				{
					Player otherPlayer = players.get(i);
					Location location = otherPlayer.getLocation();
					if(!otherPlayer.equals(player) && otherPlayer.getGameMode() == GameMode.SURVIVAL && block.getY() >= location.getBlockY() - 1 && location.distanceSquared(block.getLocation()) < minLavaDistance * minLavaDistance)
					{
						GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoLavaNearOtherPlayer, "another player");
						bucketEvent.setCancelled(true);
						return;
					}					
				}
			}
		}
		
		//log any suspicious placements (check sea level, world type, and adjacent blocks)
		if(block.getY() >= GriefPrevention.instance.getSeaLevel(block.getWorld()) - 5 && !player.hasPermission("griefprevention.lava") && block.getWorld().getEnvironment() != Environment.NETHER)
		{
		    //if certain blocks are nearby, it's less suspicious and not worth logging
		    HashSet<Material> exclusionAdjacentTypes;
		    if(bucketEvent.getBucket() == Material.WATER_BUCKET)
		        exclusionAdjacentTypes = this.commonAdjacentBlocks_water;
		    else
		        exclusionAdjacentTypes = this.commonAdjacentBlocks_lava;
		    
		    boolean makeLogEntry = true;
		    BlockFace [] adjacentDirections = new BlockFace[] {BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.DOWN};
		    for(BlockFace direction : adjacentDirections)
		    {
		        Material adjacentBlockType = block.getRelative(direction).getType();
		        if(exclusionAdjacentTypes.contains(adjacentBlockType))
	            {
		            makeLogEntry = false;
		            break;
	            }
		    }
		    
		    if(makeLogEntry)
	        {
	            GriefPrevention.AddLogEntry(player.getName() + " placed suspicious " + bucketEvent.getBucket().name() + " @ " + GriefPrevention.getfriendlyLocationString(block.getLocation()), CustomLogEntryTypes.SuspiciousActivity);
	        }
		}
	}
	
	//see above
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerBucketFill (PlayerBucketFillEvent bucketEvent)
	{
		Player player = bucketEvent.getPlayer();
		Block block = bucketEvent.getBlockClicked();
		
		if(!GriefPrevention.instance.claimsEnabledForWorld(block.getWorld())) return;
		
		//make sure the player is allowed to build at the location
		String noBuildReason = GriefPrevention.instance.allowBuild(player, block.getLocation(), Material.AIR);
		if(noBuildReason != null)
		{
		    //exemption for cow milking (permissions will be handled by player interact with entity event instead)
		    Material blockType = block.getType();
		    if(blockType == Material.AIR || blockType.isSolid()) return;
		    
			GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason);
			bucketEvent.setCancelled(true);
			return;
		}
	}
	
	//when a player interacts with the world
	@EventHandler(priority = EventPriority.LOWEST)
	void onPlayerInteract(PlayerInteractEvent event)
	{}
	
	//determines whether a block type is an inventory holder.  uses a caching strategy to save cpu time
	private ConcurrentHashMap<Integer, Boolean> inventoryHolderCache = new ConcurrentHashMap<Integer, Boolean>();
	static Block getTargetBlock(Player player, int maxDistance) throws IllegalStateException
	{
	    BlockIterator iterator = new BlockIterator(player.getLocation(), player.getEyeHeight(), maxDistance);
	    Block result = player.getLocation().getBlock().getRelative(BlockFace.UP);
	    while (iterator.hasNext())
	    {
	        result = iterator.next();
	        if(result.getType() != Material.AIR && 
	           result.getType() != Material.STATIONARY_WATER &&
	           result.getType() != Material.LONG_GRASS) return result;
	    }
	    
	    return result;
    }
}
