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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.TravelAgent;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
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

		Set<Player> recipients = event.getRecipients();
		
	
		
		//soft muted messages go out to all soft muted players
		if(this.dataStore.isSoftMuted(player.getName()))
		{
		    String notificationMessage = "(Muted " + player.getName() + "): " + message;
		    Set<Player> recipientsToKeep = new HashSet<Player>();
		    for(Player recipient : recipients)
		    {
		        if(this.dataStore.isSoftMuted(recipient.getName()))
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
		    PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		    for(Player recipient : recipients)
		    {
		        if(playerData.ignoredPlayers.containsKey(recipient.getName()))
		        {
		            recipientsToRemove.add(recipient);
		        }
		        else
		        {
		            PlayerData targetPlayerData = this.dataStore.getPlayerData(recipient.getName());
		            if(targetPlayerData.ignoredPlayers.containsKey(player.getName()))
		            {
		                recipientsToRemove.add(recipient);
		            }
		        }
		    }
		    
		    recipients.removeAll(recipientsToRemove);
		}
	}
	
	
	
	//returns true if the message should be sent, false if it should be muted 
	/*private boolean handlePlayerChat(Player player, String message, PlayerEvent event)
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
        PlayerData playerData = this.dataStore.getPlayerData(player.getName());
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
	}*/
	
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

    private ConcurrentHashMap<String, Date> lastLoginThisServerSessionMap = new ConcurrentHashMap<String, Date>();

    //counts how many players are using each IP address connected to the server right now
    private ConcurrentHashMap<String, Integer> ipCountHash = new ConcurrentHashMap<String, Integer>();
	
	//when a player attempts to join the server...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerLogin (PlayerLoginEvent event)
	{
		Player player = event.getPlayer();
		
		//remember the player's ip address
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		playerData.ipAddress = event.getAddress();
	}
	
	//when a player successfully joins the server...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	void onPlayerJoin(PlayerJoinEvent event)
	{
		Player player = event.getPlayer();
		String playerID = player.getName();
		
		//note login time
		Date nowDate = new Date();
        long now = nowDate.getTime();
		PlayerData playerData = this.dataStore.getPlayerData(playerID);
		playerData.lastSpawn = now;
		playerData.setLastLogin(nowDate);
		this.lastLoginThisServerSessionMap.put(playerID, nowDate);
		
		
		//if player has never played on the server before...
		if(!player.hasPlayedBefore())
		{
		    
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
		
        //create a thread to load ignore information
        new IgnoreLoaderThread(playerID, playerData.ignoredPlayers).start();
	}
	
	//when a player spawns, conditionally apply temporary pvp protection 
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    void onPlayerRespawn (PlayerRespawnEvent event)
    {
        Player player = event.getPlayer();
        PlayerData playerData = GriefPrevention.instance.dataStore.getPlayerData(player.getName());
        playerData.lastSpawn = Calendar.getInstance().getTimeInMillis();
        playerData.lastPvpTimestamp = 0;  //no longer in pvp combat
        
        //also send him any messaged from grief prevention he would have received while dead
        if(playerData.messageOnRespawn != null)
        {
            GriefPrevention.sendMessage(player, ChatColor.RESET /*color is alrady embedded in message in this case*/, playerData.messageOnRespawn, 40L);
            playerData.messageOnRespawn = null;
        }
    }
	
	//when a player dies...
	@EventHandler(priority = EventPriority.LOWEST)
	void onPlayerDeath(PlayerDeathEvent event)
	{
		//FEATURE: prevent death message spam by implementing a "cooldown period" for death messages
		PlayerData playerData = this.dataStore.getPlayerData(event.getEntity().getName());
		long now = Calendar.getInstance().getTimeInMillis(); 

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
	    PlayerData playerData = this.dataStore.getPlayerData(player.getName());
	    playerData.wasKicked = true;
    }
	
	//when a player quits...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerQuit(PlayerQuitEvent event)
	{
	    Player player = event.getPlayer();
		String playerID = player.getName();
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
		    this.dataStore.savePlayerData(player.getName(), playerData);
		}
		
        
        //FEATURE: during a siege, any player who logs out dies and forfeits the siege
        

        
        //drop data about this player
        this.dataStore.clearCachedPlayerData(playerID);
        
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
		
		this.dataStore.getPlayerData(player.getName());
		
		//FEATURE: players under siege or in PvP combat, can't throw items on the ground to hide 
		//them or give them away to other players before they are defeated

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
    
	//when a player interacts with an entity...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
	{
		Player player = event.getPlayer();
		Entity entity = event.getRightClicked();
		
		if(!GriefPrevention.instance.claimsEnabledForWorld(entity.getWorld())) return;

        PlayerData playerData = this.dataStore.getPlayerData(player.getName());
        
		//if entity is tameable and has an owner, apply special rules
        if(entity instanceof Tameable)
        {
            Tameable tameable = (Tameable)entity;
            if(tameable.isTamed() && tameable.getOwner() != null)
            {
               String ownerID = tameable.getOwner().getName();
               
               //if the player interacting is the owner or an admin in ignore claims mode, always allow
               if(player.getName().equals(ownerID) || playerData.ignoreClaims)
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
            }
        }
        
		
		//always allow interactions when player is in ignore claims mode
        if(playerData.ignoreClaims) return;
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
	
	//when a player interacts with the world
		@EventHandler(priority = EventPriority.LOWEST)
		void onPlayerInteract(PlayerInteractEvent event)
		{
		    //not interested in left-click-on-air actions
		    Action action = event.getAction();
		    if(action == Action.LEFT_CLICK_AIR) return;
		    if(action == Action.PHYSICAL) return;
		    
		    Player player = event.getPlayer();
			Block clickedBlock = event.getClickedBlock(); //null returned here means interacting with air
			
			Material clickedBlockType = null;
			if(clickedBlock != null)
			{
			    clickedBlockType = clickedBlock.getType();
			}
			else
			{
			    clickedBlockType = Material.AIR;
			}
			
			//don't care about left-clicking on most blocks, this is probably a break action
	        PlayerData playerData = null;

			
	

			
			
			if(clickedBlock!=null)
			{
				//ignore all actions except right-click on a block or in the air
				if(action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
				
				//what's the player holding?
				ItemStack itemInHand = player.getItemInHand();
				Material materialInHand = itemInHand.getType();		
				
				//if it's bonemeal or armor stand or spawn egg, check for build permission (ink sac == bone meal, must be a Bukkit bug?)
				if(clickedBlock != null && (materialInHand == Material.INK_SACK || materialInHand == Material.MONSTER_EGG))
				{
					String noBuildReason = GriefPrevention.instance.allowBuild(player, clickedBlock.getLocation(), clickedBlockType);
					if(noBuildReason != null)
					{
						GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason);
						event.setCancelled(true);
					}
					
					return;
				}
				
				else if(clickedBlock != null && materialInHand ==  Material.BOAT)
				{
				    if(playerData == null) playerData = this.dataStore.getPlayerData(player.getName());
				    Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
					if(claim != null)
					{
						String noAccessReason = claim.allowAccess(player);
						if(noAccessReason != null)
						{
							GriefPrevention.sendMessage(player, TextMode.Err, noAccessReason);
							event.setCancelled(true);
						}
					}
					
					return;
				}
				
				//if it's a spawn egg, minecart, or boat, and this is a creative world, apply special rules
				else if(clickedBlock != null && (materialInHand == Material.MINECART || materialInHand == Material.POWERED_MINECART || materialInHand == Material.STORAGE_MINECART || materialInHand == Material.BOAT) && GriefPrevention.instance.creativeRulesApply(clickedBlock.getLocation()))
				{
					//player needs build permission at this location
					String noBuildReason = GriefPrevention.instance.allowBuild(player, clickedBlock.getLocation(), Material.MINECART);
					if(noBuildReason != null)
					{
						GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason);
						event.setCancelled(true);
						return;
					}
				
					//enforce limit on total number of entities in this claim
					if(playerData == null) playerData = this.dataStore.getPlayerData(player.getName());
					Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
					if(claim == null) return;
					
					String noEntitiesReason = claim.allowMoreEntities();
					if(noEntitiesReason != null)
					{
						GriefPrevention.sendMessage(player, TextMode.Err, noEntitiesReason);
						event.setCancelled(true);
						return;
					}
					
					return;
				}
				
				//if he's investigating a claim
				else if(materialInHand == GriefPrevention.instance.config_claims_investigationTool)
				{
			        //if claims are disabled in this world, do nothing
				    if(!GriefPrevention.instance.claimsEnabledForWorld(player.getWorld())) return;
				    
				    //if holding shift (sneaking), show all claims in area
				    if(player.isSneaking() && player.hasPermission("griefprevention.visualizenearbyclaims"))
				    {
				        //find nearby claims
				        Set<Claim> claims = this.dataStore.getNearbyClaims(player.getLocation());
				        
				        //visualize boundaries
	                    Visualization visualization = Visualization.fromClaims(claims, player.getEyeLocation().getBlockY(), VisualizationType.Claim, player.getLocation());
	                    Visualization.Apply(player, visualization);
	                    
	                    GriefPrevention.sendMessage(player, TextMode.Info, Messages.ShowNearbyClaims, String.valueOf(claims.size()));
	                    
	                    return;
				    }
				    
				    //FEATURE: shovel and stick can be used from a distance away
			        if(action == Action.RIGHT_CLICK_AIR)
			        {
			            //try to find a far away non-air block along line of sight
			            clickedBlock = getTargetBlock(player, 100);
			            clickedBlockType = clickedBlock.getType();
			        }           
			        
			        //if no block, stop here
			        if(clickedBlock == null)
			        {
			            return;
			        }
				    
				    //air indicates too far away
					if(clickedBlockType == Material.AIR)
					{
						GriefPrevention.sendMessage(player, TextMode.Err, Messages.TooFarAway);
						Visualization.Revert(player);
						return;
					}
					
					if(playerData == null) playerData = this.dataStore.getPlayerData(player.getName());
					Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false /*ignore height*/, playerData.lastClaim);
					
					//no claim case
					if(claim == null)
					{
						GriefPrevention.sendMessage(player, TextMode.Info, Messages.BlockNotClaimed);
						Visualization.Revert(player);
					}
					
					//claim case
					else
					{
						playerData.lastClaim = claim;
						GriefPrevention.sendMessage(player, TextMode.Info, Messages.BlockClaimed, claim.getOwnerName());
						
						//visualize boundary
						Visualization visualization = Visualization.FromClaim(claim, player.getEyeLocation().getBlockY(), VisualizationType.Claim, player.getLocation());
						Visualization.Apply(player, visualization);
						
						//if can resize this claim, tell about the boundaries
						if(claim.allowEdit(player) == null)
						{
							GriefPrevention.sendMessage(player, TextMode.Info, "  " + claim.getWidth() + "x" + claim.getHeight() + "=" + claim.getArea());
						}
						
						//if deleteclaims permission, tell about the player's offline time
						if(!claim.isAdminClaim() && player.hasPermission("griefprevention.deleteclaims"))
						{
							if(claim.parent != null)
							{
							    claim = claim.parent;
							}
						    PlayerData otherPlayerData = this.dataStore.getPlayerData(claim.ownerID);
							Date lastLogin = otherPlayerData.getLastLogin();
							Date now = new Date();
							long daysElapsed = (now.getTime() - lastLogin.getTime()) / (1000 * 60 * 60 * 24); 
							
							GriefPrevention.sendMessage(player, TextMode.Info, Messages.PlayerOfflineTime, String.valueOf(daysElapsed));
							
							//drop the data we just loaded, if the player isn't online
							if(GriefPrevention.instance.getServer().getPlayer(claim.ownerID) == null)
								this.dataStore.clearCachedPlayerData(claim.ownerID);
						}
					}
					
					return;
				}
				
				//if holding a non-vanilla item
				else if(Material.getMaterial(itemInHand.getTypeId()) == null)
	            {
	                //assume it's a long range tool and project out ahead
	                if(action == Action.RIGHT_CLICK_AIR)
	                {
	                    //try to find a far away non-air block along line of sight
	                    clickedBlock = getTargetBlock(player, 100);
	                }
	                
	                //if target is claimed, require build trust permission
	                if(playerData == null) playerData = this.dataStore.getPlayerData(player.getName());
	                Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
	                if(claim != null)
	                {
	                    String reason = claim.allowBreak(player, Material.AIR);
	                    if(reason != null)
	                    {
	                        GriefPrevention.sendMessage(player, TextMode.Err, reason);
	                        event.setCancelled(true);
	                        return;
	                    }
	                }
	                
	                return;
	            }
				
				//if it's a golden shovel
				else if(materialInHand != GriefPrevention.instance.config_claims_modificationTool) return;
				
				//disable golden shovel while under siege
				if(playerData == null) playerData = this.dataStore.getPlayerData(player.getName());

				//FEATURE: shovel and stick can be used from a distance away
	            if(action == Action.RIGHT_CLICK_AIR)
	            {
	                //try to find a far away non-air block along line of sight
	                clickedBlock = getTargetBlock(player, 100);
	                clickedBlockType = clickedBlock.getType();
	            }           
	            
	            //if no block, stop here
	            if(clickedBlock == null)
	            {
	                return;
	            }
				
				//can't use the shovel from too far away
				if(clickedBlockType == Material.AIR)
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.TooFarAway);
					return;
				}
				
				//if the player is in restore nature mode, do only that
				String playerID = player.getName();
				playerData = this.dataStore.getPlayerData(player.getName());
				if(playerData.shovelMode == ShovelMode.RestoreNature || playerData.shovelMode == ShovelMode.RestoreNatureAggressive)
				{
					//if the clicked block is in a claim, visualize that claim and deliver an error message
					Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
					if(claim != null)
					{
						GriefPrevention.sendMessage(player, TextMode.Err, Messages.BlockClaimed, claim.getOwnerName());
						Visualization visualization = Visualization.FromClaim(claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());
						Visualization.Apply(player, visualization);
						
						return;
					}
					
					//figure out which chunk to repair
					Chunk chunk = player.getWorld().getChunkAt(clickedBlock.getLocation());
					
					//start the repair process
					
					//set boundaries for processing
					int miny = clickedBlock.getY();
					
					//if not in aggressive mode, extend the selection down to a little below sea level
					if(!(playerData.shovelMode == ShovelMode.RestoreNatureAggressive))
					{
						if(miny > GriefPrevention.instance.getSeaLevel(chunk.getWorld()) - 10)
						{
							miny = GriefPrevention.instance.getSeaLevel(chunk.getWorld()) - 10;
						}
					}
					
					GriefPrevention.instance.restoreChunk(chunk, miny, playerData.shovelMode == ShovelMode.RestoreNatureAggressive, 0, player);
					
					return;
				}
				
				//if in restore nature fill mode
				if(playerData.shovelMode == ShovelMode.RestoreNatureFill)
				{
					ArrayList<Material> allowedFillBlocks = new ArrayList<Material>();				
					Environment environment = clickedBlock.getWorld().getEnvironment();
					if(environment == Environment.NETHER)
					{
						allowedFillBlocks.add(Material.NETHERRACK);
					}
					else if(environment == Environment.THE_END)
					{
						allowedFillBlocks.add(Material.ENDER_STONE);
					}			
					else
					{
						allowedFillBlocks.add(Material.GRASS);
						allowedFillBlocks.add(Material.DIRT);
						allowedFillBlocks.add(Material.STONE);
						allowedFillBlocks.add(Material.SAND);
						allowedFillBlocks.add(Material.SANDSTONE);
						allowedFillBlocks.add(Material.ICE);
					}
					
					Block centerBlock = clickedBlock;
					
					int maxHeight = centerBlock.getY();
					int minx = centerBlock.getX() - playerData.fillRadius;
					int maxx = centerBlock.getX() + playerData.fillRadius;
					int minz = centerBlock.getZ() - playerData.fillRadius;
					int maxz = centerBlock.getZ() + playerData.fillRadius;				
					int minHeight = maxHeight - 10;
					if(minHeight < 0) minHeight = 0;
					
					Claim cachedClaim = null;
					for(int x = minx; x <= maxx; x++)
					{
						for(int z = minz; z <= maxz; z++)
						{
							//circular brush
							Location location = new Location(centerBlock.getWorld(), x, centerBlock.getY(), z);
							if(location.distance(centerBlock.getLocation()) > playerData.fillRadius) continue;
							
							//default fill block is initially the first from the allowed fill blocks list above
							Material defaultFiller = allowedFillBlocks.get(0);
							
							//prefer to use the block the player clicked on, if it's an acceptable fill block
							if(allowedFillBlocks.contains(centerBlock.getType()))
							{
								defaultFiller = centerBlock.getType();
							}
							
							//if the player clicks on water, try to sink through the water to find something underneath that's useful for a filler
							else if(centerBlock.getType() == Material.WATER || centerBlock.getType() == Material.STATIONARY_WATER)
							{
								Block block = centerBlock.getWorld().getBlockAt(centerBlock.getLocation());
								while(!allowedFillBlocks.contains(block.getType()) && block.getY() > centerBlock.getY() - 10)
								{
									block = block.getRelative(BlockFace.DOWN);
								}
								if(allowedFillBlocks.contains(block.getType()))
								{
									defaultFiller = block.getType();
								}
							}
							
							//fill bottom to top
							for(int y = minHeight; y <= maxHeight; y++)
							{
								Block block = centerBlock.getWorld().getBlockAt(x, y, z);
								
								//respect claims
								Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, cachedClaim);
								if(claim != null)
								{
									cachedClaim = claim;
									break;
								}
								
								//only replace air, spilling water, snow, long grass
								if(block.getType() == Material.AIR || block.getType() == Material.SNOW || (block.getType() == Material.STATIONARY_WATER && block.getData() != 0) || block.getType() == Material.LONG_GRASS)
								{							
									//if the top level, always use the default filler picked above
									if(y == maxHeight)
									{
										block.setType(defaultFiller);
									}
									
									//otherwise look to neighbors for an appropriate fill block
									else
									{
										Block eastBlock = block.getRelative(BlockFace.EAST);
										Block westBlock = block.getRelative(BlockFace.WEST);
										Block northBlock = block.getRelative(BlockFace.NORTH);
										Block southBlock = block.getRelative(BlockFace.SOUTH);
										
										//first, check lateral neighbors (ideally, want to keep natural layers)
										if(allowedFillBlocks.contains(eastBlock.getType()))
										{
											block.setType(eastBlock.getType());
										}
										else if(allowedFillBlocks.contains(westBlock.getType()))
										{
											block.setType(westBlock.getType());
										}
										else if(allowedFillBlocks.contains(northBlock.getType()))
										{
											block.setType(northBlock.getType());
										}
										else if(allowedFillBlocks.contains(southBlock.getType()))
										{
											block.setType(southBlock.getType());
										}
										
										//if all else fails, use the default filler selected above
										else
										{
											block.setType(defaultFiller);
										}
									}
								}
							}
						}
					}
					
					return;
				}
				
				//if the player doesn't have claims permission, don't do anything
				if(!player.hasPermission("griefprevention.createclaims"))
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoCreateClaimPermission);
					return;
				}
				
				//if he's resizing a claim and that claim hasn't been deleted since he started resizing it
				if(playerData.claimResizing != null && playerData.claimResizing.inDataStore)
				{
					if(clickedBlock.getLocation().equals(playerData.lastShovelLocation)) return;

					//figure out what the coords of his new claim would be
					int newx1, newx2, newz1, newz2, newy1, newy2;
					if(playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.getLesserBoundaryCorner().getBlockX())
					{
						newx1 = clickedBlock.getX();
					}
					else
					{
						newx1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockX();
					}
					
					if(playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.getGreaterBoundaryCorner().getBlockX())
					{
						newx2 = clickedBlock.getX();
					}
					else
					{
						newx2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockX();
					}
					
					if(playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.getLesserBoundaryCorner().getBlockZ())
					{
						newz1 = clickedBlock.getZ();
					}
					else
					{
						newz1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockZ();
					}
					
					if(playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.getGreaterBoundaryCorner().getBlockZ())
					{
						newz2 = clickedBlock.getZ();
					}
					else
					{
						newz2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockZ();
					}
					
					newy1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockY();
					newy2 = clickedBlock.getY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance;
					
					//for top level claims, apply size rules and claim blocks requirement
					if(playerData.claimResizing.parent == null)
					{				
						//measure new claim, apply size rules
						int newWidth = (Math.abs(newx1 - newx2) + 1);
						int newHeight = (Math.abs(newz1 - newz2) + 1);
						boolean smaller = newWidth < playerData.claimResizing.getWidth() || newHeight < playerData.claimResizing.getHeight();
								
						if(!player.hasPermission("griefprevention.adminclaims") && !playerData.claimResizing.isAdminClaim() && smaller && (newWidth < GriefPrevention.instance.config_claims_minSize || newHeight < GriefPrevention.instance.config_claims_minSize))
						{
							GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeClaimTooSmall, String.valueOf(GriefPrevention.instance.config_claims_minSize));
							return;
						}
						
						//make sure player has enough blocks to make up the difference
						if(!playerData.claimResizing.isAdminClaim() && player.getName().equals(playerData.claimResizing.getOwnerName()))
						{
							int newArea =  newWidth * newHeight;
							int blocksRemainingAfter = playerData.getRemainingClaimBlocks() + playerData.claimResizing.getArea() - newArea;
							
							if(blocksRemainingAfter < 0)
							{
								GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeNeedMoreBlocks, String.valueOf(Math.abs(blocksRemainingAfter)));
								this.tryAdvertiseAdminAlternatives(player);
								return;
							}
						}
					}
					
					//special rule for making a top-level claim smaller.  to check this, verifying the old claim's corners are inside the new claim's boundaries.
					//rule: in any mode, shrinking a claim removes any surface fluids
					Claim oldClaim = playerData.claimResizing;
					boolean smaller = false;
					if(oldClaim.parent == null)
					{				
						//temporary claim instance, just for checking contains()
						Claim newClaim = new Claim(
								new Location(oldClaim.getLesserBoundaryCorner().getWorld(), newx1, newy1, newz1), 
								new Location(oldClaim.getLesserBoundaryCorner().getWorld(), newx2, newy2, newz2),
								null, new String[]{}, new String[]{}, new String[]{}, new String[]{}, null);
						
						//if the new claim is smaller
						if(!newClaim.contains(oldClaim.getLesserBoundaryCorner(), true, false) || !newClaim.contains(oldClaim.getGreaterBoundaryCorner(), true, false))
						{
							smaller = true;
							
							//remove surface fluids about to be unclaimed
							oldClaim.removeSurfaceFluids(newClaim);
						}
					}
					
					//ask the datastore to try and resize the claim, this checks for conflicts with other claims
					CreateClaimResult result = GriefPrevention.instance.dataStore.resizeClaim(playerData.claimResizing, newx1, newx2, newy1, newy2, newz1, newz2, player);
					
					if(result.succeeded)
					{
						//decide how many claim blocks are available for more resizing
					    int claimBlocksRemaining = 0;
					    if(!playerData.claimResizing.isAdminClaim())
					    {
					        String ownerID = playerData.claimResizing.ownerID;
					        if(playerData.claimResizing.parent != null)
					        {
					            ownerID = playerData.claimResizing.parent.ownerID;
					        }
					        if(ownerID == player.getName())
					        {
					            claimBlocksRemaining = playerData.getRemainingClaimBlocks();
					        }
					        else
					        {
					            PlayerData ownerData = this.dataStore.getPlayerData(ownerID);
					            claimBlocksRemaining = ownerData.getRemainingClaimBlocks();
					            OfflinePlayer owner = GriefPrevention.instance.getServer().getOfflinePlayer(ownerID);
					            if(!owner.isOnline())
					            {
					                this.dataStore.clearCachedPlayerData(ownerID);
					            }
					        }
					    }
						
					    //inform about success, visualize, communicate remaining blocks available
					    GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClaimResizeSuccess, String.valueOf(claimBlocksRemaining));
						Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.Claim, player.getLocation());
						Visualization.Apply(player, visualization);
						
						//if resizing someone else's claim, make a log entry
						if(!playerID.equals(playerData.claimResizing.ownerID) && playerData.claimResizing.parent == null)
						{
							GriefPrevention.AddLogEntry(player.getName() + " resized " + playerData.claimResizing.getOwnerName() + "'s claim at " + GriefPrevention.getfriendlyLocationString(playerData.claimResizing.lesserBoundaryCorner) + ".");
						}
						
						//if increased to a sufficiently large size and no subdivisions yet, send subdivision instructions
						if(oldClaim.getArea() < 1000 && result.claim.getArea() >= 1000 && result.claim.children.size() == 0 && !player.hasPermission("griefprevention.adminclaims"))
						{
						  GriefPrevention.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
		                  GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L, DataStore.SUBDIVISION_VIDEO_URL);
						}
						
						//if in a creative mode world and shrinking an existing claim, restore any unclaimed area
						if(smaller && GriefPrevention.instance.creativeRulesApply(oldClaim.getLesserBoundaryCorner()))
						{
							GriefPrevention.sendMessage(player, TextMode.Warn, Messages.UnclaimCleanupWarning);
							GriefPrevention.instance.restoreClaim(oldClaim, 20L * 60 * 2);  //2 minutes
							GriefPrevention.AddLogEntry(player.getName() + " shrank a claim @ " + GriefPrevention.getfriendlyLocationString(playerData.claimResizing.getLesserBoundaryCorner()));
						}
						
						//clean up
						playerData.claimResizing = null;
						playerData.lastShovelLocation = null;
					}
					else
					{
						if(result.claim != null)
						{
	    				    //inform player
	    					GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlap);
	    					
	    					//show the player the conflicting claim
	    					Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());
	    					Visualization.Apply(player, visualization);
						}
						else
						{
						    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapRegion);
						}
					}
					
					return;
				}
				
				//otherwise, since not currently resizing a claim, must be starting a resize, creating a new claim, or creating a subdivision
				Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), true /*ignore height*/, playerData.lastClaim);			
				
				//if within an existing claim, he's not creating a new one
				if(claim != null)
				{
					//if the player has permission to edit the claim or subdivision
					String noEditReason = claim.allowEdit(player);
					if(noEditReason == null)
					{
						//if he clicked on a corner, start resizing it
						if((clickedBlock.getX() == claim.getLesserBoundaryCorner().getBlockX() || clickedBlock.getX() == claim.getGreaterBoundaryCorner().getBlockX()) && (clickedBlock.getZ() == claim.getLesserBoundaryCorner().getBlockZ() || clickedBlock.getZ() == claim.getGreaterBoundaryCorner().getBlockZ()))
						{
							playerData.claimResizing = claim;
							playerData.lastShovelLocation = clickedBlock.getLocation();
							GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ResizeStart);
						}
						
						//if he didn't click on a corner and is in subdivision mode, he's creating a new subdivision
						else if(playerData.shovelMode == ShovelMode.Subdivide)
						{
							//if it's the first click, he's trying to start a new subdivision
							if(playerData.lastShovelLocation == null)
							{						
								//if the clicked claim was a subdivision, tell him he can't start a new subdivision here
								if(claim.parent != null)
								{
									GriefPrevention.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapSubdivision);							
								}
							
								//otherwise start a new subdivision
								else
								{
									GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionStart);
									playerData.lastShovelLocation = clickedBlock.getLocation();
									playerData.claimSubdividing = claim;
								}
							}
							
							//otherwise, he's trying to finish creating a subdivision by setting the other boundary corner
							else
							{
								//if last shovel location was in a different world, assume the player is starting the create-claim workflow over
								if(!playerData.lastShovelLocation.getWorld().equals(clickedBlock.getWorld()))
								{
									playerData.lastShovelLocation = null;
									this.onPlayerInteract(event);
									return;
								}
								
								//try to create a new claim (will return null if this subdivision overlaps another)
								CreateClaimResult result = this.dataStore.createClaim(
										player.getWorld(), 
										playerData.lastShovelLocation.getBlockX(), clickedBlock.getX(), 
										playerData.lastShovelLocation.getBlockY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance, clickedBlock.getY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance, 
										playerData.lastShovelLocation.getBlockZ(), clickedBlock.getZ(), 
										null,  //owner is not used for subdivisions
										playerData.claimSubdividing,
										null, player);
								
								//if it didn't succeed, tell the player why
								if(!result.succeeded)
								{
									GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateSubdivisionOverlap);
																					
									Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());
									Visualization.Apply(player, visualization);
									
									return;
								}
								
								//otherwise, advise him on the /trust command and show him his new subdivision
								else
								{					
									GriefPrevention.sendMessage(player, TextMode.Success, Messages.SubdivisionSuccess);
									Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.Claim, player.getLocation());
									Visualization.Apply(player, visualization);
									playerData.lastShovelLocation = null;
									playerData.claimSubdividing = null;
								}
							}
						}
						
						//otherwise tell him he can't create a claim here, and show him the existing claim
						//also advise him to consider /abandonclaim or resizing the existing claim
						else
						{						
							GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlap);
							Visualization visualization = Visualization.FromClaim(claim, clickedBlock.getY(), VisualizationType.Claim, player.getLocation());
							Visualization.Apply(player, visualization);
						}
					}
					
					//otherwise tell the player he can't claim here because it's someone else's claim, and show him the claim
					else
					{
						GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapOtherPlayer, claim.getOwnerName());
						Visualization visualization = Visualization.FromClaim(claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());
						Visualization.Apply(player, visualization);
					}
					
					return;
				}
				
				//otherwise, the player isn't in an existing claim!
				
				//if he hasn't already start a claim with a previous shovel action
				Location lastShovelLocation = playerData.lastShovelLocation;
				if(lastShovelLocation == null)
				{
					//if claims are not enabled in this world and it's not an administrative claim, display an error message and stop
					if(!GriefPrevention.instance.claimsEnabledForWorld(player.getWorld()))
					{
						GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimsDisabledWorld);
						return;
					}
					
					//if he's at the claim count per player limit already and doesn't have permission to bypass, display an error message
					if(GriefPrevention.instance.config_claims_maxClaimsPerPlayer > 0 &&
					   !player.hasPermission("griefprevention.overrideclaimcountlimit") &&
					   playerData.getClaims().size() >= GriefPrevention.instance.config_claims_maxClaimsPerPlayer)
					{
					    GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimCreationFailedOverClaimCountLimit);
					    return;
					}
					
					//remember it, and start him on the new claim
					playerData.lastShovelLocation = clickedBlock.getLocation();
					GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimStart);
					
					//show him where he's working
					Visualization visualization = Visualization.FromClaim(new Claim(clickedBlock.getLocation(), clickedBlock.getLocation(), null, new String[]{}, new String[]{}, new String[]{}, new String[]{}, null), clickedBlock.getY(), VisualizationType.RestoreNature, player.getLocation());
					Visualization.Apply(player, visualization);
				}
				
				//otherwise, he's trying to finish creating a claim by setting the other boundary corner
				else
				{
					//if last shovel location was in a different world, assume the player is starting the create-claim workflow over
					if(!lastShovelLocation.getWorld().equals(clickedBlock.getWorld()))
					{
						playerData.lastShovelLocation = null;
						this.onPlayerInteract(event);
						return;
					}
					
					//apply minimum claim dimensions rule
					int newClaimWidth = Math.abs(playerData.lastShovelLocation.getBlockX() - clickedBlock.getX()) + 1;
					int newClaimHeight = Math.abs(playerData.lastShovelLocation.getBlockZ() - clickedBlock.getZ()) + 1;
					
					if(playerData.shovelMode != ShovelMode.Admin && (newClaimWidth < GriefPrevention.instance.config_claims_minSize || newClaimHeight < GriefPrevention.instance.config_claims_minSize))
					{
						//this IF block is a workaround for craftbukkit bug which fires two events for one interaction
					    if(newClaimWidth != 1 && newClaimHeight != 1)
					    {
					        GriefPrevention.sendMessage(player, TextMode.Err, Messages.NewClaimTooSmall, String.valueOf(GriefPrevention.instance.config_claims_minSize));
					    }
						return;
					}
					
					//if not an administrative claim, verify the player has enough claim blocks for this new claim
					if(playerData.shovelMode != ShovelMode.Admin)
					{					
						int newClaimArea = newClaimWidth * newClaimHeight; 
						int remainingBlocks = playerData.getRemainingClaimBlocks();
						if(newClaimArea > remainingBlocks)
						{
							GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimInsufficientBlocks, String.valueOf(newClaimArea - remainingBlocks));
							this.tryAdvertiseAdminAlternatives(player);
							return;
						}
					}					
					else
					{
						playerID = null;
					}
					
					//try to create a new claim
					CreateClaimResult result = this.dataStore.createClaim(
							player.getWorld(), 
							lastShovelLocation.getBlockX(), clickedBlock.getX(), 
							lastShovelLocation.getBlockY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance, clickedBlock.getY() - GriefPrevention.instance.config_claims_claimsExtendIntoGroundDistance, 
							lastShovelLocation.getBlockZ(), clickedBlock.getZ(), 
							playerID,
							null, null,
							player);
					
					//if it didn't succeed, tell the player why
					if(!result.succeeded)
					{
						if(result.claim != null)
						{
	    				    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);
	    					
	    					Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());
	    					Visualization.Apply(player, visualization);
						}
						else
						{
						    GriefPrevention.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
						}
	    					
						return;
					}
					
					//otherwise, advise him on the /trust command and show him his new claim
					else
					{					
						GriefPrevention.sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);
						Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.Claim, player.getLocation());
						Visualization.Apply(player, visualization);
						playerData.lastShovelLocation = null;
						
						//if it's a big claim, tell the player about subdivisions
						if(!player.hasPermission("griefprevention.adminclaims") && result.claim.getArea() >= 1000)
			            {
			                GriefPrevention.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
			                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L, DataStore.SUBDIVISION_VIDEO_URL);
			            }
					}
				}
			}
		}
		
		//educates a player about /adminclaims and /acb, if he can use them 
		private void tryAdvertiseAdminAlternatives(Player player)
		{
	        if(player.hasPermission("griefprevention.adminclaims") && player.hasPermission("griefprevention.adjustclaimblocks"))
	        {
	            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseACandACB);
	        }
	        else if(player.hasPermission("griefprevention.adminclaims"))
	        {
	            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseAdminClaims);
	        }
	        else if(player.hasPermission("griefprevention.adjustclaimblocks"))
	        {
	            GriefPrevention.sendMessage(player, TextMode.Info, Messages.AdvertiseACB);
	        }
	    }

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
	
	@EventHandler
	public void onopeninventory(InventoryOpenEvent e){
		 HumanEntity ent = e.getPlayer();
		 if(!(ent instanceof Player) )return;
		 Player p = (Player)ent;
		 Claim c = dataStore.getClaimAt(p.getLocation(),true,null);
		 if(c!=null)return;
		 
		 if( c.allowContainers(p)==null ){
			 p.closeInventory();
		 }
		 
	}
	
	@EventHandler
	public void inventoryclick(InventoryClickEvent e){
		 HumanEntity ent = e.getWhoClicked();
		 if(!(ent instanceof Player) )return;
		 Player p = (Player)ent;
		 Claim c = dataStore.getClaimAt(p.getLocation(),true,null);
		 if(c!=null)return;
		 
		 if( c.allowContainers(p)==null ){
			 e.setCancelled(true);
			 p.closeInventory();
		 }
	}
}
