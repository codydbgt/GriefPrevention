/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.ryanhamshire.GriefPrevention.DataStore.NoTransferException;
import net.milkbowl.vault.economy.Economy; 

import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockIterator;

public class GriefPrevention extends JavaPlugin
{
	//for convenience, a reference to the instance of this plugin
	public static GriefPrevention instance;
	
	//for logging to the console and log file
	private static Logger log = Logger.getLogger("Minecraft");
	
	//this handles data storage, like player and region data
	public DataStore dataStore;
	
	//this tracks item stacks expected to drop which will need protection
    ArrayList<PendingItemProtection> pendingItemWatchList = new ArrayList<PendingItemProtection>();
    
    //log entry manager for GP's custom log files
    CustomLogger customLogger;
	
	//configuration variables, loaded/saved from a config.yml
	
	//claim mode for each world
	public ConcurrentHashMap<World, ClaimsMode> config_claims_worldModes;     
	
	public int config_claims_maxClaimsPerPlayer;                    //maximum number of claims per player
	public boolean config_claims_respectWorldGuard;                 //whether claim creations requires WG build permission in creation area
	public boolean config_claims_portalsRequirePermission;          //whether nether portals require permission to generate.  defaults to off for performance reasons
	public boolean config_claims_villagerTradingRequiresTrust;      //whether trading with a claimed villager requires permission
	
	public int config_claims_initialBlocks;							//the number of claim blocks a new player starts with
	public double config_claims_abandonReturnRatio;                 //the portion of claim blocks returned to a player when a claim is abandoned
	public int config_claims_blocksAccruedPerHour;					//how many additional blocks players get each hour of play (can be zero)
	public int config_claims_maxAccruedBlocks;						//the limit on accrued blocks (over time).  doesn't limit purchased or admin-gifted blocks 
	public int config_claims_maxDepth;								//limit on how deep claims can go
	public int config_claims_expirationDays;						//how many days of inactivity before a player loses his claims
	
	public int config_claims_automaticClaimsForNewPlayersRadius;	//how big automatic new player claims (when they place a chest) should be.  0 to disable
	public int config_claims_claimsExtendIntoGroundDistance;		//how far below the shoveled block a new claim will reach
	public int config_claims_minSize;								//minimum width and height for non-admin claims
	
	public int config_claims_chestClaimExpirationDays;				//number of days of inactivity before an automatic chest claim will be deleted
	public int config_claims_unusedClaimExpirationDays;				//number of days of inactivity before an unused (nothing build) claim will be deleted
	public boolean config_claims_survivalAutoNatureRestoration;		//whether survival claims will be automatically restored to nature when auto-deleted
	
	public Material config_claims_investigationTool;				//which material will be used to investigate claims with a right click
	public Material config_claims_modificationTool;	  				//which material will be used to create/resize claims with a right click
	
	public ArrayList<String> config_claims_commandsRequiringAccessTrust; //the list of slash commands requiring access trust when in a claim
	public boolean config_claims_supplyPlayerManual;                //whether to give new players a book with land claim help in it 

	
	public double config_economy_claimBlocksPurchaseCost;			//cost to purchase a claim block.  set to zero to disable purchase.
	public double config_economy_claimBlocksSellValue;				//return on a sold claim block.  set to zero to disable sale.
	
	public HashMap<String, Integer> config_seaLevelOverride;		//override for sea level, because bukkit doesn't report the right value for all situations
	
	//public boolean config_limitTreeGrowth;                          //whether trees should be prevented from growing into a claim from outside
	
	//custom log settings
	public int config_logs_daysToKeep;
    public boolean config_logs_socialEnabled;
    public boolean config_logs_suspiciousEnabled;
    public boolean config_logs_adminEnabled;
    public boolean config_logs_debugEnabled;
	
	private String databaseUrl;
	private String databaseUserName;
	private String databasePassword;
	
	//reference to the economy plugin, if economy integration is enabled
	public static Economy economy = null;					
	
	//how far away to search from a tree trunk for its branch blocks
	public static final int TREE_RADIUS = 5;
	
	//how long to wait before deciding a player is staying online or staying offline, for notication messages
	public static final int NOTIFICATION_SECONDS = 20;
	
	//adds a server log entry
	public static synchronized void AddLogEntry(String entry, CustomLogEntryTypes customLogType, boolean excludeFromServerLogs)
	{
		if(customLogType != null && GriefPrevention.instance.customLogger != null)
		{
		    GriefPrevention.instance.customLogger.AddEntry(entry, customLogType);
		}
	    if(!excludeFromServerLogs) log.info("GriefPrevention: " + entry);
	}
	
	public static synchronized void AddLogEntry(String entry, CustomLogEntryTypes customLogType)
    {
        AddLogEntry(entry, customLogType, false);
    }
	
	public static synchronized void AddLogEntry(String entry)
    {
        AddLogEntry(entry, CustomLogEntryTypes.Debug);
    }
	
	//initializes well...   everything
	public void onEnable()
	{ 		
	    instance = this;
        
        AddLogEntry("Grief Prevention boot start.");
		
		this.loadConfig();
		
		this.customLogger = new CustomLogger();
        
		AddLogEntry("Finished loading configuration.");
		
		//when datastore initializes, it loads player and claim data, and posts some stats to the log
		if(this.databaseUrl.length() > 0)
		{
			try
			{
				DatabaseDataStore databaseStore = new DatabaseDataStore(this.databaseUrl, this.databaseUserName, this.databasePassword);
			
				if(FlatFileDataStore.hasData())
				{
					GriefPrevention.AddLogEntry("There appears to be some data on the hard drive.  Migrating those data to the database...");
					FlatFileDataStore flatFileStore = new FlatFileDataStore();
					this.dataStore = flatFileStore;
					flatFileStore.migrateData(databaseStore);
					GriefPrevention.AddLogEntry("Data migration process complete.  Reloading data from the database...");
					databaseStore.close();
					databaseStore = new DatabaseDataStore(this.databaseUrl, this.databaseUserName, this.databasePassword);
				}
				
				this.dataStore = databaseStore;
			}
			catch(Exception e)
			{
				GriefPrevention.AddLogEntry("Because there was a problem with the database, GriefPrevention will not function properly.  Either update the database config settings resolve the issue, or delete those lines from your config.yml so that GriefPrevention can use the file system to store data.");
				e.printStackTrace();
				return;
			}			
		}
		
		//if not using the database because it's not configured or because there was a problem, use the file system to store data
		//this is the preferred method, as it's simpler than the database scenario
		if(this.dataStore == null)
		{
			File oldclaimdata = new File(getDataFolder(), "ClaimData");
			if(oldclaimdata.exists()) {
				if(!FlatFileDataStore.hasData()) {
					File claimdata = new File("plugins" + File.separator + "GriefPreventionData" + File.separator + "ClaimData");
					oldclaimdata.renameTo(claimdata);
					File oldplayerdata = new File(getDataFolder(), "PlayerData");
					File playerdata = new File("plugins" + File.separator + "GriefPreventionData" + File.separator + "PlayerData");
					oldplayerdata.renameTo(playerdata);
				}
			}
			try
			{
				this.dataStore = new FlatFileDataStore();
			}
			catch(Exception e)
			{
				GriefPrevention.AddLogEntry("Unable to initialize the file system data store.  Details:");
				GriefPrevention.AddLogEntry(e.getMessage());
				e.printStackTrace();
			}
		}
		
		String dataMode = (this.dataStore instanceof FlatFileDataStore)?"(File Mode)":"(Database Mode)";
		AddLogEntry("Finished loading data " + dataMode + ".");
		
		//unless claim block accrual is disabled, start the recurring per 10 minute event to give claim blocks to online players
		//20L ~ 1 second
		if(this.config_claims_blocksAccruedPerHour > 0)
		{
			DeliverClaimBlocksTask task = new DeliverClaimBlocksTask(null);
			this.getServer().getScheduler().scheduleSyncRepeatingTask(this, task, 20L * 60 * 10, 20L * 60 * 10);
		}
		
		//start recurring cleanup scan for unused claims belonging to inactive players
		CleanupUnusedClaimsTask task2 = new CleanupUnusedClaimsTask();
		this.getServer().getScheduler().scheduleSyncRepeatingTask(this, task2, 20L * 60 * 2, 20L * 60 * 5);
		
		//register for events
		PluginManager pluginManager = this.getServer().getPluginManager();
		
		//player events
		PlayerEventHandler playerEventHandler = new PlayerEventHandler(this.dataStore, this);
		pluginManager.registerEvents(playerEventHandler, this);
		
		//block events
		BlockEventHandler blockEventHandler = new BlockEventHandler(this.dataStore);
		pluginManager.registerEvents(blockEventHandler, this);
			
		
		//if economy is enabled
		if(this.config_economy_claimBlocksPurchaseCost > 0 || this.config_economy_claimBlocksSellValue > 0)
		{
			//try to load Vault
			GriefPrevention.AddLogEntry("GriefPrevention requires Vault for economy integration.");
			GriefPrevention.AddLogEntry("Attempting to load Vault...");
			RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
			GriefPrevention.AddLogEntry("Vault loaded successfully!");
			
			//ask Vault to hook into an economy plugin
			GriefPrevention.AddLogEntry("Looking for a Vault-compatible economy plugin...");
			if (economyProvider != null) 
	        {
	        	GriefPrevention.economy = economyProvider.getProvider();
	            
	            //on success, display success message
				if(GriefPrevention.economy != null)
		        {
	            	GriefPrevention.AddLogEntry("Hooked into economy: " + GriefPrevention.economy.getName() + ".");  
	            	GriefPrevention.AddLogEntry("Ready to buy/sell claim blocks!");
		        }
		        
				//otherwise error message
				else
		        {
		        	GriefPrevention.AddLogEntry("ERROR: Vault was unable to find a supported economy plugin.  Either install a Vault-compatible economy plugin, or set both of the economy config variables to zero.");
		        }	            
	        }
			
			//another error case
			else
			{
				GriefPrevention.AddLogEntry("ERROR: Vault was unable to find a supported economy plugin.  Either install a Vault-compatible economy plugin, or set both of the economy config variables to zero.");
			}
		}
		
		//load ignore lists for any already-online players
		Player[] players = GriefPrevention.instance.getServer().getOnlinePlayers();
		for(Player player : players)
		{
		    new IgnoreLoaderThread(player.getName(), this.dataStore.getPlayerData(player.getName()).ignoredPlayers).start();
		}
		
		AddLogEntry("Boot finished.");
	}
	
	private void loadConfig()
	{
	    //load the config if it exists
        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(DataStore.configFilePath));
        FileConfiguration outConfig = new YamlConfiguration();
        
        //read configuration settings (note defaults)
        
        //get (deprecated node) claims world names from the config file
        List<World> worlds = this.getServer().getWorlds();
        List<String> deprecated_claimsEnabledWorldNames = config.getStringList("GriefPrevention.Claims.Worlds");
        
        //validate that list
        for(int i = 0; i < deprecated_claimsEnabledWorldNames.size(); i++)
        {
            String worldName = deprecated_claimsEnabledWorldNames.get(i);
            World world = this.getServer().getWorld(worldName);
            if(world == null)
            {
                deprecated_claimsEnabledWorldNames.remove(i--);
            }
        }
        
        //get (deprecated node) creative world names from the config file
        List<String> deprecated_creativeClaimsEnabledWorldNames = config.getStringList("GriefPrevention.Claims.CreativeRulesWorlds");
        
        //validate that list
        for(int i = 0; i < deprecated_creativeClaimsEnabledWorldNames.size(); i++)
        {
            String worldName = deprecated_creativeClaimsEnabledWorldNames.get(i);
            World world = this.getServer().getWorld(worldName);
            if(world == null)
            {
                deprecated_claimsEnabledWorldNames.remove(i--);
            }
        }
        
        //decide claim mode for each world
        this.config_claims_worldModes = new ConcurrentHashMap<World, ClaimsMode>();
        for(World world : worlds)
        {
            //is it specified in the config file?
            String configSetting = config.getString("GriefPrevention.Claims.Mode." + world.getName());
            if(configSetting != null)
            {
                ClaimsMode claimsMode = this.configStringToClaimsMode(configSetting);
                if(claimsMode != null)
                {
                    this.config_claims_worldModes.put(world, claimsMode);
                    continue;
                }
                else
                {
                    GriefPrevention.AddLogEntry("Error: Invalid claim mode \"" + configSetting + "\".  Options are Survival, Creative, and Disabled.");
                    this.config_claims_worldModes.put(world, ClaimsMode.Creative);
                }
            }
            
            //was it specified in a deprecated config node?
            if(deprecated_creativeClaimsEnabledWorldNames.contains(world.getName()))
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Creative);
            }
            
            else if(deprecated_claimsEnabledWorldNames.contains(world.getName()))
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            }
            
            //does the world's name indicate its purpose?
            else if(world.getName().toLowerCase().contains("survival"))
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            }
            
            else if(world.getName().toLowerCase().contains("creative"))
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Creative);
            }
            
            //decide a default based on server type and world type
            else if(this.getServer().getDefaultGameMode() == GameMode.CREATIVE)
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Creative);
            }
            
            else if(world.getEnvironment() == Environment.NORMAL)
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            }
            
            else
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Disabled);
            }
            
            //if the setting WOULD be disabled but this is a server upgrading from the old config format,
            //then default to survival mode for safety's sake (to protect any admin claims which may 
            //have been created there)
            if(this.config_claims_worldModes.get(world) == ClaimsMode.Disabled &&
               deprecated_claimsEnabledWorldNames.size() > 0)
            {
                this.config_claims_worldModes.put(world, ClaimsMode.Survival);
            }
        }
        
        //sea level
        this.config_seaLevelOverride = new HashMap<String, Integer>();
        for(int i = 0; i < worlds.size(); i++)
        {
            int seaLevelOverride = config.getInt("GriefPrevention.SeaLevelOverrides." + worlds.get(i).getName(), -1);
            outConfig.set("GriefPrevention.SeaLevelOverrides." + worlds.get(i).getName(), seaLevelOverride);
            this.config_seaLevelOverride.put(worlds.get(i).getName(), seaLevelOverride);
        }
        
        this.config_claims_initialBlocks = config.getInt("GriefPrevention.Claims.InitialBlocks", 100);
        this.config_claims_blocksAccruedPerHour = config.getInt("GriefPrevention.Claims.BlocksAccruedPerHour", 100);
        this.config_claims_maxAccruedBlocks = config.getInt("GriefPrevention.Claims.MaxAccruedBlocks", 80000);
        this.config_claims_abandonReturnRatio = config.getDouble("GriefPrevention.Claims.AbandonReturnRatio", 1);
        this.config_claims_automaticClaimsForNewPlayersRadius = config.getInt("GriefPrevention.Claims.AutomaticNewPlayerClaimsRadius", 4);
        this.config_claims_claimsExtendIntoGroundDistance = Math.abs(config.getInt("GriefPrevention.Claims.ExtendIntoGroundDistance", 5));
        this.config_claims_minSize = config.getInt("GriefPrevention.Claims.MinimumSize", 10);
        this.config_claims_maxDepth = config.getInt("GriefPrevention.Claims.MaximumDepth", 0);
        this.config_claims_chestClaimExpirationDays = config.getInt("GriefPrevention.Claims.Expiration.ChestClaimDays", 7);
        this.config_claims_unusedClaimExpirationDays = config.getInt("GriefPrevention.Claims.Expiration.UnusedClaimDays", 14);
        this.config_claims_expirationDays = config.getInt("GriefPrevention.Claims.Expiration.AllClaimDays", 0);
        this.config_claims_survivalAutoNatureRestoration = config.getBoolean("GriefPrevention.Claims.Expiration.AutomaticNatureRestoration.SurvivalWorlds", false);
        this.config_claims_maxClaimsPerPlayer = config.getInt("GriefPrevention.Claims.MaximumNumberOfClaimsPerPlayer", 0);
        this.config_claims_respectWorldGuard = config.getBoolean("GriefPrevention.Claims.CreationRequiresWorldGuardBuildPermission", true);
        this.config_claims_portalsRequirePermission = config.getBoolean("GriefPrevention.Claims.PortalGenerationRequiresPermission", false);
        this.config_claims_villagerTradingRequiresTrust = config.getBoolean("GriefPrevention.Claims.VillagerTradingRequiresPermission", true);
        String accessTrustSlashCommands = config.getString("GriefPrevention.Claims.CommandsRequiringAccessTrust", "/sethome");
        this.config_claims_supplyPlayerManual = config.getBoolean("GriefPrevention.Claims.DeliverManuals", true);
        
        String bannedPvPCommandsList = config.getString("GriefPrevention.PvP.BlockedSlashCommands", "/home;/vanish;/spawn;/tpa");
        
        this.config_economy_claimBlocksPurchaseCost = config.getDouble("GriefPrevention.Economy.ClaimBlocksPurchaseCost", 0);
        this.config_economy_claimBlocksSellValue = config.getDouble("GriefPrevention.Economy.ClaimBlocksSellValue", 0);

        //default for claim investigation tool
        String investigationToolMaterialName = Material.STICK.name();
        
        //get investigation tool from config
        investigationToolMaterialName = config.getString("GriefPrevention.Claims.InvestigationTool", investigationToolMaterialName);
        
        //validate investigation tool
        this.config_claims_investigationTool = Material.getMaterial(investigationToolMaterialName);
        if(this.config_claims_investigationTool == null)
        {
            GriefPrevention.AddLogEntry("ERROR: Material " + investigationToolMaterialName + " not found.  Defaulting to the stick.  Please update your config.yml.");
            this.config_claims_investigationTool = Material.STICK;
        }
        
        //default for claim creation/modification tool
        String modificationToolMaterialName = Material.GOLD_SPADE.name();
        
        //get modification tool from config
        modificationToolMaterialName = config.getString("GriefPrevention.Claims.ModificationTool", modificationToolMaterialName);
        
        //validate modification tool
        this.config_claims_modificationTool = Material.getMaterial(modificationToolMaterialName);
        if(this.config_claims_modificationTool == null)
        {
            GriefPrevention.AddLogEntry("ERROR: Material " + modificationToolMaterialName + " not found.  Defaulting to the golden shovel.  Please update your config.yml.");
            this.config_claims_modificationTool = Material.GOLD_SPADE;
        }

        //optional database settings
        this.databaseUrl = config.getString("GriefPrevention.Database.URL", "");
        this.databaseUserName = config.getString("GriefPrevention.Database.UserName", "");
        this.databasePassword = config.getString("GriefPrevention.Database.Password", "");
        
        //custom logger settings
        this.config_logs_daysToKeep = config.getInt("GriefPrevention.Abridged Logs.Days To Keep", 7);
        this.config_logs_socialEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Social Activity", true);
        this.config_logs_suspiciousEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Suspicious Activity", true);
        this.config_logs_adminEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Administrative Activity", false);
        this.config_logs_debugEnabled = config.getBoolean("GriefPrevention.Abridged Logs.Included Entry Types.Debug", false);
        
        //claims mode by world
        for(World world : this.config_claims_worldModes.keySet())
        {
            outConfig.set(
                "GriefPrevention.Claims.Mode." + world.getName(), 
                this.config_claims_worldModes.get(world).name());
        }
        
        outConfig.set("GriefPrevention.Claims.InitialBlocks", this.config_claims_initialBlocks);
        outConfig.set("GriefPrevention.Claims.BlocksAccruedPerHour", this.config_claims_blocksAccruedPerHour);
        outConfig.set("GriefPrevention.Claims.MaxAccruedBlocks", this.config_claims_maxAccruedBlocks);
        outConfig.set("GriefPrevention.Claims.AbandonReturnRatio", this.config_claims_abandonReturnRatio);
        outConfig.set("GriefPrevention.Claims.AutomaticNewPlayerClaimsRadius", this.config_claims_automaticClaimsForNewPlayersRadius);
        outConfig.set("GriefPrevention.Claims.ExtendIntoGroundDistance", this.config_claims_claimsExtendIntoGroundDistance);
        outConfig.set("GriefPrevention.Claims.MinimumSize", this.config_claims_minSize);
        outConfig.set("GriefPrevention.Claims.MaximumDepth", this.config_claims_maxDepth);
        outConfig.set("GriefPrevention.Claims.InvestigationTool", this.config_claims_investigationTool.name());
        outConfig.set("GriefPrevention.Claims.ModificationTool", this.config_claims_modificationTool.name());
        outConfig.set("GriefPrevention.Claims.Expiration.ChestClaimDays", this.config_claims_chestClaimExpirationDays);
        outConfig.set("GriefPrevention.Claims.Expiration.UnusedClaimDays", this.config_claims_unusedClaimExpirationDays);       
        outConfig.set("GriefPrevention.Claims.Expiration.AllClaimDays", this.config_claims_expirationDays);
        outConfig.set("GriefPrevention.Claims.Expiration.AutomaticNatureRestoration.SurvivalWorlds", this.config_claims_survivalAutoNatureRestoration);
        outConfig.set("GriefPrevention.Claims.MaximumNumberOfClaimsPerPlayer", this.config_claims_maxClaimsPerPlayer);
        outConfig.set("GriefPrevention.Claims.CreationRequiresWorldGuardBuildPermission", this.config_claims_respectWorldGuard);
        outConfig.set("GriefPrevention.Claims.PortalGenerationRequiresPermission", this.config_claims_portalsRequirePermission);
        outConfig.set("GriefPrevention.Claims.VillagerTradingRequiresPermission", this.config_claims_villagerTradingRequiresTrust);
        outConfig.set("GriefPrevention.Claims.CommandsRequiringAccessTrust", accessTrustSlashCommands);
        outConfig.set("GriefPrevention.Claims.DeliverManuals", config_claims_supplyPlayerManual);

        //custom logger settings
        outConfig.set("GriefPrevention.Abridged Logs.Days To Keep", this.config_logs_daysToKeep);
        outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Social Activity", this.config_logs_socialEnabled);
        outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Suspicious Activity", this.config_logs_suspiciousEnabled);
        outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Administrative Activity", this.config_logs_adminEnabled);
        outConfig.set("GriefPrevention.Abridged Logs.Included Entry Types.Debug", this.config_logs_debugEnabled);
        
        try
        {
            outConfig.save(DataStore.configFilePath);
        }
        catch(IOException exception)
        {
            AddLogEntry("Unable to write to the configuration file at \"" + DataStore.configFilePath + "\"");
        }
        
        //try to parse the list of commands requiring access trust in land claims
        this.config_claims_commandsRequiringAccessTrust = new ArrayList<String>();
        String [] commands = accessTrustSlashCommands.split(";");
        for(int i = 0; i < commands.length; i++)
        {
            if(!commands[i].isEmpty())
            {
                this.config_claims_commandsRequiringAccessTrust.add(commands[i].trim().toLowerCase());
            }
        }
        
    }

    private ClaimsMode configStringToClaimsMode(String configSetting)
    {
        if(configSetting.equalsIgnoreCase("Survival"))
        {
            return ClaimsMode.Survival;
        }
        else if(configSetting.equalsIgnoreCase("Creative"))
        {
            return ClaimsMode.Creative;
        }
        else if(configSetting.equalsIgnoreCase("Disabled"))
        {
            return ClaimsMode.Disabled;
        }
        else if(configSetting.equalsIgnoreCase("SurvivalRequiringClaims"))
        {
            return ClaimsMode.SurvivalRequiringClaims;
        }
        else
        {
            return null;
        }
    }

    //handles slash commands
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args){
		
		Player player = null;
		if (sender instanceof Player) 
		{
			player = (Player) sender;
		}
		
		//abandonclaim
		if(cmd.getName().equalsIgnoreCase("abandonclaim") && player != null)
		{
			return this.abandonClaimHandler(player, false);
		}		
		
		//abandontoplevelclaim
		if(cmd.getName().equalsIgnoreCase("abandontoplevelclaim") && player != null)
		{
			return this.abandonClaimHandler(player, true);
		}
		
		//ignoreclaims
		if(cmd.getName().equalsIgnoreCase("ignoreclaims") && player != null)
		{
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			
			playerData.ignoreClaims = !playerData.ignoreClaims;
			
			//toggle ignore claims mode on or off
			if(!playerData.ignoreClaims)
			{
				GriefPrevention.sendMessage(player, TextMode.Success, Messages.RespectingClaims);
			}
			else
			{
				GriefPrevention.sendMessage(player, TextMode.Success, Messages.IgnoringClaims);
			}
			
			return true;
		}
		
		//abandonallclaims
		else if(cmd.getName().equalsIgnoreCase("abandonallclaims") && player != null)
		{
			if(args.length != 0) return false;
			
			//count claims
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			int originalClaimCount = playerData.getClaims().size();
			
			//check count
			if(originalClaimCount == 0)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.YouHaveNoClaims);
				return true;
			}
			
			//adjust claim blocks
			for(Claim claim : playerData.getClaims())
			{
			    playerData.setAccruedClaimBlocks(playerData.getAccruedClaimBlocks() - (int)Math.ceil((claim.getArea() * (1 - this.config_claims_abandonReturnRatio))));
			}
			
			//delete them
			this.dataStore.deleteClaimsForPlayer(player.getName(), false);
			
			//inform the player
			int remainingBlocks = playerData.getRemainingClaimBlocks();
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.SuccessfulAbandon, String.valueOf(remainingBlocks));
			
			//revert any current visualization
			Visualization.Revert(player);
			
			return true;
		}
		
		//restore nature
		else if(cmd.getName().equalsIgnoreCase("restorenature") && player != null)
		{
			//change shovel mode
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			playerData.shovelMode = ShovelMode.RestoreNature;
			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.RestoreNatureActivate);
			return true;
		}
		
		//restore nature aggressive mode
		else if(cmd.getName().equalsIgnoreCase("restorenatureaggressive") && player != null)
		{
			//change shovel mode
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			playerData.shovelMode = ShovelMode.RestoreNatureAggressive;
			GriefPrevention.sendMessage(player, TextMode.Warn, Messages.RestoreNatureAggressiveActivate);
			return true;
		}
		
		//restore nature fill mode
		else if(cmd.getName().equalsIgnoreCase("restorenaturefill") && player != null)
		{
			//change shovel mode
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			playerData.shovelMode = ShovelMode.RestoreNatureFill;
			
			//set radius based on arguments
			playerData.fillRadius = 2;
			if(args.length > 0)
			{
				try
				{
					playerData.fillRadius = Integer.parseInt(args[0]);
				}
				catch(Exception exception){ }
			}
			
			if(playerData.fillRadius < 0) playerData.fillRadius = 2;
			
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.FillModeActive, String.valueOf(playerData.fillRadius));
			return true;
		}
		
		//trust <player>
		else if(cmd.getName().equalsIgnoreCase("trust") && player != null)
		{
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;
			
			//most trust commands use this helper method, it keeps them consistent
			this.handleTrustCommand(player, ClaimPermission.Build, args[0]);
			
			return true;
		}
		
		//transferclaim <player>
		else if(cmd.getName().equalsIgnoreCase("transferclaim") && player != null)
		{
			//which claim is the user in?
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), true, null);
			if(claim == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Instr, Messages.TransferClaimMissing);
				return true;
			}
			
			//check additional permission for admin claims
            if(claim.isAdminClaim() && !player.hasPermission("griefprevention.adminclaims"))
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.TransferClaimPermission);
                return true;
            }
            
			String ownerName = "null";
			
			if(args.length > 0)
			{
    			OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
    			if(targetPlayer == null)
    			{
    				GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
    				return true;
    			}
    			ownerName = targetPlayer.getName();
			}
			
			//change ownerhsip
			try
			{
				this.dataStore.changeClaimOwner(claim, ownerName);
			}
			catch(NoTransferException e)
			{
			    GriefPrevention.sendMessage(player, TextMode.Instr, Messages.TransferTopLevel);
    			return true;
			}
			
			//confirm
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.TransferSuccess);
			GriefPrevention.AddLogEntry(player.getName() + " transferred a claim at " + GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()) + " to " + ownerName + ".", CustomLogEntryTypes.AdminActivity);
			
			return true;
		}
		
		//trustlist
		else if(cmd.getName().equalsIgnoreCase("trustlist") && player != null)
		{
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), true, null);
			
			//if no claim here, error message
			if(claim == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrustListNoClaim);
				return true;
			}
			
			//if no permission to manage permissions, error message
			String errorMessage = claim.allowGrantPermission(player);
			if(errorMessage != null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, errorMessage);
				return true;
			}
			
			//otherwise build a list of explicit permissions by permission level
			//and send that to the player
			ArrayList<String> builders = new ArrayList<String>();
			ArrayList<String> containers = new ArrayList<String>();
			ArrayList<String> accessors = new ArrayList<String>();
			ArrayList<String> managers = new ArrayList<String>();
			claim.getPermissions(builders, containers, accessors, managers);
			
			GriefPrevention.sendMessage(player, TextMode.Info, Messages.TrustListHeader);
			
			StringBuilder permissions = new StringBuilder();
			permissions.append(ChatColor.GOLD + ">");
			
			if(managers.size() > 0)
			{
				for(int i = 0; i < managers.size(); i++)
					permissions.append(managers.get(i) + " ");
			}
			
			player.sendMessage(permissions.toString());
			permissions = new StringBuilder();
			permissions.append(ChatColor.YELLOW + ">");
			
			if(builders.size() > 0)
			{				
				for(int i = 0; i < builders.size(); i++)
					permissions.append(builders.get(i) + " ");		
			}
			
			player.sendMessage(permissions.toString());
			permissions = new StringBuilder();
			permissions.append(ChatColor.GREEN + ">");				
			
			if(containers.size() > 0)
			{
				for(int i = 0; i < containers.size(); i++)
					permissions.append(containers.get(i) + " ");		
			}
			
			player.sendMessage(permissions.toString());
			permissions = new StringBuilder();
			permissions.append(ChatColor.BLUE + ">");
				
			if(accessors.size() > 0)
			{
				for(int i = 0; i < accessors.size(); i++)
					permissions.append(accessors.get(i) + " ");			
			}
			
			player.sendMessage(permissions.toString());
			
			player.sendMessage(
		        ChatColor.GOLD + this.dataStore.getMessage(Messages.Manage) + " " + 
		        ChatColor.YELLOW + this.dataStore.getMessage(Messages.Build) + " " + 
		        ChatColor.GREEN + this.dataStore.getMessage(Messages.Containers) + " " + 
		        ChatColor.BLUE + this.dataStore.getMessage(Messages.Access));
			
			return true;
		}
		
		//untrust <player> or untrust [<group>]
		else if(cmd.getName().equalsIgnoreCase("untrust") && player != null)
		{
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;
			
			//determine which claim the player is standing in
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);
			
			//bracket any permissions
			if(args[0].contains(".") && !args[0].startsWith("[") && !args[0].endsWith("]"))
			{
				args[0] = "[" + args[0] + "]";
			}
			
			//determine whether a single player or clearing permissions entirely
			boolean clearPermissions = false;
			OfflinePlayer otherPlayer = null;
			if(args[0].equals("all"))				
			{
				if(claim == null || claim.allowEdit(player) == null)
				{
					clearPermissions = true;
				}
				else
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClearPermsOwnerOnly);
					return true;
				}
			}
			
			else
			{
				//validate player argument or group argument
				if(!args[0].startsWith("[") || !args[0].endsWith("]"))
				{
					otherPlayer = this.resolvePlayerByName(args[0]);
					if(!clearPermissions && otherPlayer == null && !args[0].equals("public"))
					{
						GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
						return true;
					}
					
					//correct to proper casing
					if(otherPlayer != null)
						args[0] = otherPlayer.getName();
				}
			}
			
			//if no claim here, apply changes to all his claims
			if(claim == null)
			{
				PlayerData playerData = this.dataStore.getPlayerData(player.getName());
				for(int i = 0; i < playerData.getClaims().size(); i++)
				{
					claim = playerData.getClaims().get(i);
					
					//if untrusting "all" drop all permissions
					if(clearPermissions)
					{	
						claim.clearPermissions();
					}
					
					//otherwise drop individual permissions
					else
					{
						String idToDrop = args[0];
					    if(otherPlayer != null)
						{
						    idToDrop = otherPlayer.getName().toString(); 
						}
					    claim.dropPermission(idToDrop);
						claim.managers.remove(idToDrop);
					}
					
					//save changes
					this.dataStore.saveClaim(claim);
				}
				
				//beautify for output
				if(args[0].equals("public"))
				{
					args[0] = "the public";
				}
				
				//confirmation message
				if(!clearPermissions)
				{
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustIndividualAllClaims, args[0]);
				}
				else
				{
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustEveryoneAllClaims);
				}
			}			
			
			//otherwise, apply changes to only this claim
			else if(claim.allowGrantPermission(player) != null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionTrust, claim.getOwnerName());
				return true;
			}
			else
			{
				//if clearing all
				if(clearPermissions)
				{
					//requires owner
				    if(claim.allowEdit(player) != null)
				    {
				        GriefPrevention.sendMessage(player, TextMode.Err, Messages.UntrustAllOwnerOnly);
				        return true;
				    }
				    
				    claim.clearPermissions();
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.ClearPermissionsOneClaim);
				}
				
				//otherwise individual permission drop
				else
				{
				    String idToDrop = args[0];
                    if(otherPlayer != null)
                    {
                        idToDrop = otherPlayer.getName().toString(); 
                    }
				    boolean targetIsManager = claim.managers.contains(idToDrop);
                    if(targetIsManager && claim.allowEdit(player) != null)  //only claim owners can untrust managers
					{
                        GriefPrevention.sendMessage(player, TextMode.Err, Messages.ManagersDontUntrustManagers, claim.getOwnerName());
                        return true;
					}
                    else
                    {
				        claim.dropPermission(idToDrop);
	                    claim.managers.remove(idToDrop);
						
						//beautify for output
						if(args[0].equals("public"))
						{
							args[0] = "the public";
						}
						
						GriefPrevention.sendMessage(player, TextMode.Success, Messages.UntrustIndividualSingleClaim, args[0]);
					}
				}
				
				//save changes
				this.dataStore.saveClaim(claim);										
			}
			
			return true;
		}
		
		//accesstrust <player>
		else if(cmd.getName().equalsIgnoreCase("accesstrust") && player != null)
		{
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;
			
			this.handleTrustCommand(player, ClaimPermission.Access, args[0]);
			
			return true;
		}
		
		//containertrust <player>
		else if(cmd.getName().equalsIgnoreCase("containertrust") && player != null)
		{
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;
			
			this.handleTrustCommand(player, ClaimPermission.Inventory, args[0]);
			
			return true;
		}
		
		//permissiontrust <player>
		else if(cmd.getName().equalsIgnoreCase("permissiontrust") && player != null)
		{
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;
			
			this.handleTrustCommand(player, null, args[0]);  //null indicates permissiontrust to the helper method
			
			return true;
		}
		
		//buyclaimblocks
		else if(cmd.getName().equalsIgnoreCase("buyclaimblocks") && player != null)
		{
			//if economy is disabled, don't do anything
			if(GriefPrevention.economy == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.BuySellNotConfigured);
				return true;
			}
			
			if(!player.hasPermission("griefprevention.buysellclaimblocks"))
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
				return true;
			}
			
			//if purchase disabled, send error message
			if(GriefPrevention.instance.config_economy_claimBlocksPurchaseCost == 0)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.OnlySellBlocks);
				return true;
			}
			
			//if no parameter, just tell player cost per block and balance
			if(args.length != 1)
			{
				GriefPrevention.sendMessage(player, TextMode.Info, Messages.BlockPurchaseCost, String.valueOf(GriefPrevention.instance.config_economy_claimBlocksPurchaseCost), String.valueOf(GriefPrevention.economy.getBalance(player)));
				return false;
			}
			
			else
			{
				PlayerData playerData = this.dataStore.getPlayerData(player.getName());
				
				//try to parse number of blocks
				int blockCount;
				try
				{
					blockCount = Integer.parseInt(args[0]);
				}
				catch(NumberFormatException numberFormatException)
				{
					return false;  //causes usage to be displayed
				}
				
				if(blockCount <= 0)
				{
					return false;
				}
				
				//if the player can't afford his purchase, send error message
				double balance = economy.getBalance(player);				
				double totalCost = blockCount * GriefPrevention.instance.config_economy_claimBlocksPurchaseCost;				
				if(totalCost > balance)
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.InsufficientFunds, String.valueOf(totalCost),  String.valueOf(balance));
				}
				
				//otherwise carry out transaction
				else
				{
					//withdraw cost
					economy.withdrawPlayer(player, totalCost);
					
					//add blocks
					playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + blockCount);
					this.dataStore.savePlayerData(player.getName(), playerData);
					
					//inform player
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.PurchaseConfirmation, String.valueOf(totalCost), String.valueOf(playerData.getRemainingClaimBlocks()));
				}
				
				return true;
			}
		}
		
		//sellclaimblocks <amount> 
		else if(cmd.getName().equalsIgnoreCase("sellclaimblocks") && player != null)
		{
			//if economy is disabled, don't do anything
			if(GriefPrevention.economy == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.BuySellNotConfigured);
				return true;
			}
			
			if(!player.hasPermission("griefprevention.buysellclaimblocks"))
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionForCommand);
				return true;
			}
			
			//if disabled, error message
			if(GriefPrevention.instance.config_economy_claimBlocksSellValue == 0)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.OnlyPurchaseBlocks);
				return true;
			}
			
			//load player data
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			int availableBlocks = playerData.getRemainingClaimBlocks();
			
			//if no amount provided, just tell player value per block sold, and how many he can sell
			if(args.length != 1)
			{
				GriefPrevention.sendMessage(player, TextMode.Info, Messages.BlockSaleValue, String.valueOf(GriefPrevention.instance.config_economy_claimBlocksSellValue), String.valueOf(availableBlocks));
				return false;
			}
						
			//parse number of blocks
			int blockCount;
			try
			{
				blockCount = Integer.parseInt(args[0]);
			}
			catch(NumberFormatException numberFormatException)
			{
				return false;  //causes usage to be displayed
			}
			
			if(blockCount <= 0)
			{
				return false;
			}
			
			//if he doesn't have enough blocks, tell him so
			if(blockCount > availableBlocks)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotEnoughBlocksForSale);
			}
			
			//otherwise carry out the transaction
			else
			{					
				//compute value and deposit it
				double totalValue = blockCount * GriefPrevention.instance.config_economy_claimBlocksSellValue;					
				economy.depositPlayer(player, totalValue);
				
				//subtract blocks
				playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() - blockCount);
				this.dataStore.savePlayerData(player.getName(), playerData);
				
				//inform player
				GriefPrevention.sendMessage(player, TextMode.Success, Messages.BlockSaleConfirmation, String.valueOf(totalValue), String.valueOf(playerData.getRemainingClaimBlocks()));
			}
			
			return true;
		}		
		
		//adminclaims
		else if(cmd.getName().equalsIgnoreCase("adminclaims") && player != null)
		{
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			playerData.shovelMode = ShovelMode.Admin;
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.AdminClaimsMode);
			
			return true;
		}
		
		//basicclaims
		else if(cmd.getName().equalsIgnoreCase("basicclaims") && player != null)
		{
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			playerData.shovelMode = ShovelMode.Basic;
			playerData.claimSubdividing = null;
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.BasicClaimsMode);
			
			return true;
		}
		
		//subdivideclaims
		else if(cmd.getName().equalsIgnoreCase("subdivideclaims") && player != null)
		{
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			playerData.shovelMode = ShovelMode.Subdivide;
			playerData.claimSubdividing = null;
			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionMode);
			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, DataStore.SUBDIVISION_VIDEO_URL);
			
			return true;
		}
		
		//deleteclaim
		else if(cmd.getName().equalsIgnoreCase("deleteclaim") && player != null)
		{
			//determine which claim the player is standing in
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);
			
			if(claim == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.DeleteClaimMissing);
			}
			
			else 
			{
				//deleting an admin claim additionally requires the adminclaims permission
				if(!claim.isAdminClaim() || player.hasPermission("griefprevention.adminclaims"))
				{
					PlayerData playerData = this.dataStore.getPlayerData(player.getName());
					if(claim.children.size() > 0 && !playerData.warnedAboutMajorDeletion)
					{
						GriefPrevention.sendMessage(player, TextMode.Warn, Messages.DeletionSubdivisionWarning);
						playerData.warnedAboutMajorDeletion = true;
					}
					else
					{
						claim.removeSurfaceFluids(null);
						this.dataStore.deleteClaim(claim, true);
						
						//if in a creative mode world, /restorenature the claim
						if(GriefPrevention.instance.creativeRulesApply(claim.getLesserBoundaryCorner()))
						{
							GriefPrevention.instance.restoreClaim(claim, 0);
						}
						
						GriefPrevention.sendMessage(player, TextMode.Success, Messages.DeleteSuccess);
						GriefPrevention.AddLogEntry(player.getName() + " deleted " + claim.getOwnerName() + "'s claim at " + GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()), CustomLogEntryTypes.AdminActivity);
						
						//revert any current visualization
						Visualization.Revert(player);
						
						playerData.warnedAboutMajorDeletion = false;
					}
				}
				else
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.CantDeleteAdminClaim);
				}
			}

			return true;
		}
		
		else if(cmd.getName().equalsIgnoreCase("claimexplosions") && player != null)
		{
			//determine which claim the player is standing in
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);
			
			if(claim == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.DeleteClaimMissing);
			}
			
			else
			{
				String noBuildReason = claim.allowBuild(player, Material.TNT);
				if(noBuildReason != null)
				{
					GriefPrevention.sendMessage(player, TextMode.Err, noBuildReason);
					return true;
				}
				
				if(claim.areExplosivesAllowed)
				{
					claim.areExplosivesAllowed = false;
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.ExplosivesDisabled);
				}
				else
				{
					claim.areExplosivesAllowed = true;
					GriefPrevention.sendMessage(player, TextMode.Success, Messages.ExplosivesEnabled);
				}
			}

			return true;
		}
		
		//deleteallclaims <player>
		else if(cmd.getName().equalsIgnoreCase("deleteallclaims"))
		{
			//requires exactly one parameter, the other player's name
			if(args.length != 1) return false;
			
			//try to find that player
			OfflinePlayer otherPlayer = this.resolvePlayerByName(args[0]);
			if(otherPlayer == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return true;
			}
			
			//delete all that player's claims
			this.dataStore.deleteClaimsForPlayer(otherPlayer.getName(), true);
			
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.DeleteAllSuccess, otherPlayer.getName());
			if(player != null)
			{
				GriefPrevention.AddLogEntry(player.getName() + " deleted all claims belonging to " + otherPlayer.getName() + ".", CustomLogEntryTypes.AdminActivity);
			
				//revert any current visualization
				Visualization.Revert(player);
			}
			
			return true;
		}
		
		//claimslist or claimslist <player>
		else if(cmd.getName().equalsIgnoreCase("claimslist"))
		{
			//at most one parameter
			if(args.length > 1) return false;
			
			//player whose claims will be listed
			OfflinePlayer otherPlayer;
			
			//if another player isn't specified, assume current player
			if(args.length < 1)
			{
				if(player != null)
					otherPlayer = player;
				else
					return false;
			}
			
			//otherwise if no permission to delve into another player's claims data
			else if(player != null && !player.hasPermission("griefprevention.claimslistother"))
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.ClaimsListNoPermission);
				return true;
			}
						
			//otherwise try to find the specified player
			else
			{
				otherPlayer = this.resolvePlayerByName(args[0]);
				if(otherPlayer == null)
				{
					GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
					return true;
				}
			}
			
			//load the target player's data
			PlayerData playerData = this.dataStore.getPlayerData(otherPlayer.getName());
			Vector<Claim> claims = playerData.getClaims();
			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.StartBlockMath, 
		        String.valueOf(playerData.getAccruedClaimBlocks()), 
		        String.valueOf((playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getName()))), 
		        String.valueOf((playerData.getAccruedClaimBlocks() + playerData.getBonusClaimBlocks() + this.dataStore.getGroupBonusBlocks(otherPlayer.getName()))));
			if(claims.size() > 0)
			{
    			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimsListHeader);
    			for(int i = 0; i < playerData.getClaims().size(); i++)
    			{
    				Claim claim = playerData.getClaims().get(i);
    				GriefPrevention.sendMessage(player, TextMode.Instr, getfriendlyLocationString(claim.getLesserBoundaryCorner()) + this.dataStore.getMessage(Messages.ContinueBlockMath, String.valueOf(claim.getArea())));
    			}
			
				GriefPrevention.sendMessage(player, TextMode.Instr, Messages.EndBlockMath, String.valueOf(playerData.getRemainingClaimBlocks()));
			}
			
			//drop the data we just loaded, if the player isn't online
			if(!otherPlayer.isOnline())
				this.dataStore.clearCachedPlayerData(otherPlayer.getName());
			
			return true;
		}
		
		//adminclaimslist
        else if(cmd.getName().equalsIgnoreCase("adminclaimslist"))
        {
            //find admin claims
            Vector<Claim> claims = new Vector<Claim>();
            for(Claim claim : this.dataStore.claims)
            {
                if(claim.ownerID == null)  //admin claim
                {
                    claims.add(claim);
                }
            }
            if(claims.size() > 0)
            {
                GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ClaimsListHeader);
                for(int i = 0; i < claims.size(); i++)
                {
                    Claim claim = claims.get(i);
                    GriefPrevention.sendMessage(player, TextMode.Instr, getfriendlyLocationString(claim.getLesserBoundaryCorner()));
                }
            }

            return true;
        }
		
		//unlockItems
		else if(cmd.getName().equalsIgnoreCase("unlockdrops") && player != null)
		{
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		    playerData.dropsAreUnlocked = true;
		    GriefPrevention.sendMessage(player, TextMode.Success, Messages.DropUnlockConfirmation);
			
			return true;
		}
		
		//deletealladminclaims
		else if(player != null && cmd.getName().equalsIgnoreCase("deletealladminclaims"))
		{
			if(!player.hasPermission("griefprevention.deleteclaims"))
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoDeletePermission);
				return true;
			}
			
			//delete all admin claims
			this.dataStore.deleteClaimsForPlayer(null, true);  //null for owner id indicates an administrative claim
			
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.AllAdminDeleted);
			if(player != null)
			{
				GriefPrevention.AddLogEntry(player.getName() + " deleted all administrative claims.", CustomLogEntryTypes.AdminActivity);
			
				//revert any current visualization
				Visualization.Revert(player);
			}
			
			return true;
		}
		
		//adjustbonusclaimblocks <player> <amount> or [<permission>] amount
		else if(cmd.getName().equalsIgnoreCase("adjustbonusclaimblocks"))
		{
			//requires exactly two parameters, the other player or group's name and the adjustment
			if(args.length != 2) return false;
			
			//parse the adjustment amount
			int adjustment;			
			try
			{
				adjustment = Integer.parseInt(args[1]);
			}
			catch(NumberFormatException numberFormatException)
			{
				return false;  //causes usage to be displayed
			}
			
			//if granting blocks to all players with a specific permission
			if(args[0].startsWith("[") && args[0].endsWith("]"))
			{
				String permissionIdentifier = args[0].substring(1, args[0].length() - 1);
				int newTotal = this.dataStore.adjustGroupBonusBlocks(permissionIdentifier, adjustment);
				
				GriefPrevention.sendMessage(player, TextMode.Success, Messages.AdjustGroupBlocksSuccess, permissionIdentifier, String.valueOf(adjustment), String.valueOf(newTotal));
				if(player != null) GriefPrevention.AddLogEntry(player.getName() + " adjusted " + permissionIdentifier + "'s bonus claim blocks by " + adjustment + ".");
				
				return true;
			}
			
			//otherwise, find the specified player
			OfflinePlayer targetPlayer;
			try
			{
			    String playerID = args[0];
			    targetPlayer = this.getServer().getOfflinePlayer(playerID);
			    
			}
			catch(IllegalArgumentException e)
			{
    			targetPlayer = this.resolvePlayerByName(args[0]);
			}
			
			if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
			
			//give blocks to player
			PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getName());
			playerData.setBonusClaimBlocks(playerData.getBonusClaimBlocks() + adjustment);
			this.dataStore.savePlayerData(targetPlayer.getName(), playerData);
			
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.AdjustBlocksSuccess, targetPlayer.getName(), String.valueOf(adjustment), String.valueOf(playerData.getBonusClaimBlocks()));
			if(player != null) GriefPrevention.AddLogEntry(player.getName() + " adjusted " + targetPlayer.getName() + "'s bonus claim blocks by " + adjustment + ".", CustomLogEntryTypes.AdminActivity);
			
			return true;			
		}
		
		//setaccruedclaimblocks <player> <amount>
        else if(cmd.getName().equalsIgnoreCase("setaccruedclaimblocks"))
        {
            //requires exactly two parameters, the other player's name and the new amount
            if(args.length != 2) return false;
            
            //parse the adjustment amount
            int newAmount;         
            try
            {
                newAmount = Integer.parseInt(args[1]);
            }
            catch(NumberFormatException numberFormatException)
            {
                return false;  //causes usage to be displayed
            }
            
            //find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            //set player's blocks
            PlayerData playerData = this.dataStore.getPlayerData(targetPlayer.getName());
            playerData.setAccruedClaimBlocks(newAmount);
            this.dataStore.savePlayerData(targetPlayer.getName(), playerData);
            
            GriefPrevention.sendMessage(player, TextMode.Success, Messages.SetClaimBlocksSuccess);
            if(player != null) GriefPrevention.AddLogEntry(player.getName() + " set " + targetPlayer.getName() + "'s accrued claim blocks to " + newAmount + ".", CustomLogEntryTypes.AdminActivity);
            
            return true;
        }
		
		//trapped
		else if(cmd.getName().equalsIgnoreCase("trapped") && player != null)
		{
			//FEATURE: empower players who get "stuck" in an area where they don't have permission to build to save themselves
			
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			Claim claim = this.dataStore.getClaimAt(player.getLocation(), false, playerData.lastClaim);
			
			//if another /trapped is pending, ignore this slash command
			if(playerData.pendingTrapped)
			{
				return true;
			}
			
			//if the player isn't in a claim or has permission to build, tell him to man up
			if(claim == null || claim.allowBuild(player, Material.AIR) == null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotTrappedHere);				
				return true;
			}
			
			//if the player is in the nether or end, he's screwed (there's no way to programmatically find a safe place for him)
			if(player.getWorld().getEnvironment() != Environment.NORMAL)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);				
				return true;
			}
			
			//if the player is in an administrative claim, he should contact an admin
			if(claim.isAdminClaim())
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.TrappedWontWorkHere);
				return true;
			}
			
			//send instructions
			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.RescuePending);
			
			//create a task to rescue this player in a little while
			PlayerRescueTask task = new PlayerRescueTask(player, player.getLocation());
			this.getServer().getScheduler().scheduleSyncDelayedTask(this, task, 200L);  //20L ~ 1 second
			
			return true;
		}
		
		else if(cmd.getName().equalsIgnoreCase("softmute"))
		{
		    //requires one parameter
		    if(args.length != 1) return false;
		    
		    //find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            //toggle mute for player
            boolean isMuted = this.dataStore.toggleSoftMute(targetPlayer.getName());
            if(isMuted)
            {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.SoftMuted, targetPlayer.getName());
            }
            else
            {
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.UnSoftMuted, targetPlayer.getName());
            }
            
            return true;
		}
		
		else if(cmd.getName().equalsIgnoreCase("gpreload"))
		{
		    this.loadConfig();
		    if(player != null)
		    {
		        GriefPrevention.sendMessage(player, TextMode.Success, "Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server.");
		    }
		    else
		    {
		        GriefPrevention.AddLogEntry("Configuration updated.  If you have updated your Grief Prevention JAR, you still need to /reload or reboot your server.");
		    }
		    
		    return true;
		}
		
		//givepet
		else if(cmd.getName().equalsIgnoreCase("givepet") && player != null)
		{
		    //requires one parameter
            if(args.length < 1) return false;
            
            PlayerData playerData = this.dataStore.getPlayerData(player.getName());
            
            //special case: cancellation
            if(args[0].equalsIgnoreCase("cancel"))
            {
                playerData.petGiveawayRecipient = null;
                GriefPrevention.sendMessage(player, TextMode.Success, Messages.PetTransferCancellation);
                return true;
            }
            
            //find the specified player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            //remember the player's ID for later pet transfer
            playerData.petGiveawayRecipient = targetPlayer;
            
            //send instructions
            GriefPrevention.sendMessage(player, TextMode.Instr, Messages.ReadyToTransferPet);
            
            return true;
		}
		
		//gpblockinfo
		else if(cmd.getName().equalsIgnoreCase("gpblockinfo") && player != null)
		{
		    ItemStack inHand = player.getItemInHand();
		    player.sendMessage("In Hand: " + String.format("%s(%d:%d)", inHand.getType().name(), inHand.getTypeId(), inHand.getData().getData()));
		    
		    Block inWorld = GriefPrevention.getTargetNonAirBlock(player, 300);
		    player.sendMessage("In World: " + String.format("%s(%d:%d)", inWorld.getType().name(), inWorld.getTypeId(), inWorld.getData()));
		    
		    return true;
		}
		
		//ignoreplayer
        else if(cmd.getName().equalsIgnoreCase("ignoreplayer") && player != null)
        {
            //requires target player name
            if(args.length < 1) return false;
            
            //validate target player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            this.setIgnoreStatus(player, targetPlayer, IgnoreMode.StandardIgnore);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.IgnoreConfirmation);
            
            return true;
        }
		
		//unignoreplayer
        else if(cmd.getName().equalsIgnoreCase("unignoreplayer") && player != null)
        {
            //requires target player name
            if(args.length < 1) return false;
            
            //validate target player
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            PlayerData playerData = this.dataStore.getPlayerData(player.getName());
            Boolean ignoreStatus = playerData.ignoredPlayers.get(targetPlayer.getName());
            if(ignoreStatus == null || ignoreStatus == true)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotIgnoringPlayer);
                return true;
            }
            
            this.setIgnoreStatus(player, targetPlayer, IgnoreMode.None);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.UnIgnoreConfirmation);
            
            return true;
        }
		
		//ignoredplayerlist
        else if(cmd.getName().equalsIgnoreCase("ignoredplayerlist") && player != null)
        {
            PlayerData playerData = this.dataStore.getPlayerData(player.getName());
            StringBuilder builder = new StringBuilder();
            for(Entry<String, Boolean> entry : playerData.ignoredPlayers.entrySet())
            {
                if(entry.getValue() != null)
                {
                    //if not an admin ignore, add it to the list
                    if(!entry.getValue())
                    {
                        builder.append(entry.getKey());
                        builder.append(" ");
                    }
                }
            }
            
            String list = builder.toString().trim();
            if(list.isEmpty())
            {
                GriefPrevention.sendMessage(player, TextMode.Info, Messages.NotIgnoringAnyone);
            }
            else
            {
                GriefPrevention.sendMessage(player, TextMode.Info, list);
            }
            
            return true;
        }
		
		//separateplayers
        else if(cmd.getName().equalsIgnoreCase("separate") && player != null)
        {
            //requires two player names
            if(args.length < 2) return false;
            
            //validate target players
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
            if(targetPlayer2 == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            this.setIgnoreStatus(targetPlayer, targetPlayer2, IgnoreMode.AdminIgnore);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.SeparateConfirmation);
            
            return true;
        }
		
		//unseparateplayers
        else if(cmd.getName().equalsIgnoreCase("unseparate") && player != null)
        {
            //requires two player names
            if(args.length < 2) return false;
            
            //validate target players
            OfflinePlayer targetPlayer = this.resolvePlayerByName(args[0]);
            if(targetPlayer == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            OfflinePlayer targetPlayer2 = this.resolvePlayerByName(args[1]);
            if(targetPlayer2 == null)
            {
                GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
                return true;
            }
            
            this.setIgnoreStatus(targetPlayer, targetPlayer2, IgnoreMode.None);
            this.setIgnoreStatus(targetPlayer2, targetPlayer, IgnoreMode.None);

            GriefPrevention.sendMessage(player, TextMode.Success, Messages.UnSeparateConfirmation);
            
            return true;
        }
		
		return false; 
	}
	
	void setIgnoreStatus(OfflinePlayer ignorer, OfflinePlayer ignoree, IgnoreMode mode)
	{
	    PlayerData playerData = this.dataStore.getPlayerData(ignorer.getName());
        if(mode == IgnoreMode.None)
        {
            playerData.ignoredPlayers.remove(ignoree.getName());
        }
        else
        {
            playerData.ignoredPlayers.put(ignoree.getName(), mode == IgnoreMode.StandardIgnore ? false : true);
        }
        
        playerData.ignoreListChanged = true;
        if(!ignorer.isOnline())
        {
            this.dataStore.savePlayerData(ignorer.getName(), playerData);
            this.dataStore.clearCachedPlayerData(ignorer.getName());
        }
	}
	
	enum IgnoreMode	{None, StandardIgnore, AdminIgnore}


    public static String getfriendlyLocationString(Location location) 
	{
		return location.getWorld().getName() + ": x" + location.getBlockX() + ", z" + location.getBlockZ();
	}

	private boolean abandonClaimHandler(Player player, boolean deleteTopLevelClaim) 
	{
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		
		//which claim is being abandoned?
		Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);
		if(claim == null)
		{
			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.AbandonClaimMissing);
		}
		
		//verify ownership
		else if(claim.allowEdit(player) != null)
		{
			GriefPrevention.sendMessage(player, TextMode.Err, Messages.NotYourClaim);
		}
		
		//warn if has children and we're not explicitly deleting a top level claim
		else if(claim.children.size() > 0 && !deleteTopLevelClaim)
		{
			GriefPrevention.sendMessage(player, TextMode.Instr, Messages.DeleteTopLevelClaim);
			return true;
		}
		
		else
		{
			//delete it
			claim.removeSurfaceFluids(null);
			this.dataStore.deleteClaim(claim, true);
			
			//if in a creative mode world, restore the claim area
			if(GriefPrevention.instance.creativeRulesApply(claim.getLesserBoundaryCorner()))
			{
				GriefPrevention.AddLogEntry(player.getName() + " abandoned a claim @ " + GriefPrevention.getfriendlyLocationString(claim.getLesserBoundaryCorner()));
				GriefPrevention.sendMessage(player, TextMode.Warn, Messages.UnclaimCleanupWarning);
				GriefPrevention.instance.restoreClaim(claim, 20L * 60 * 2);
			}
			
			//adjust claim blocks when abandoning a top level claim
			if(claim.parent == null)
			{
			    playerData.setAccruedClaimBlocks(playerData.getAccruedClaimBlocks() - (int)Math.ceil((claim.getArea() * (1 - this.config_claims_abandonReturnRatio))));
			}
			
			//tell the player how many claim blocks he has left
			int remainingBlocks = playerData.getRemainingClaimBlocks();
			GriefPrevention.sendMessage(player, TextMode.Success, Messages.AbandonSuccess, String.valueOf(remainingBlocks));
			
			//revert any current visualization
			Visualization.Revert(player);
			
			playerData.warnedAboutMajorDeletion = false;
		}
		
		return true;
		
	}

	//helper method keeps the trust commands consistent and eliminates duplicate code
	private void handleTrustCommand(Player player, ClaimPermission permissionLevel, String recipientName) 
	{
		//determine which claim the player is standing in
		Claim claim = this.dataStore.getClaimAt(player.getLocation(), true /*ignore height*/, null);
		
		//validate player or group argument
		String permission = null; 
		OfflinePlayer otherPlayer = null;
		String recipientID = null;
		if(recipientName.startsWith("[") && recipientName.endsWith("]"))
		{
			permission = recipientName.substring(1, recipientName.length() - 1);
			if(permission == null || permission.isEmpty())
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.InvalidPermissionID);
				return;
			}
		}
		
		else if(recipientName.contains("."))
		{
			permission = recipientName;
		}
		
		else
		{		
			otherPlayer = this.resolvePlayerByName(recipientName);
			if(otherPlayer == null && !recipientName.equals("public") && !recipientName.equals("all"))
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.PlayerNotFound2);
				return;
			}
			
			if(otherPlayer != null)
			{
				recipientName = otherPlayer.getName();
				recipientID = otherPlayer.getName();
			}
			else
			{
				recipientName = "public";
			}
		}
		
		//determine which claims should be modified
		ArrayList<Claim> targetClaims = new ArrayList<Claim>();
		if(claim == null)
		{
			PlayerData playerData = this.dataStore.getPlayerData(player.getName());
			for(int i = 0; i < playerData.getClaims().size(); i++)
			{
				targetClaims.add(playerData.getClaims().get(i));
			}
		}
		else
		{
			//check permission here
			if(claim.allowGrantPermission(player) != null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.NoPermissionTrust, claim.getOwnerName());
				return;
			}
			
			//see if the player has the level of permission he's trying to grant
			String errorMessage = null;
			
			//permission level null indicates granting permission trust
			if(permissionLevel == null)
			{
				errorMessage = claim.allowEdit(player);
				if(errorMessage != null)
				{
					errorMessage = "Only " + claim.getOwnerName() + " can grant /PermissionTrust here."; 
				}
			}
			
			//otherwise just use the ClaimPermission enum values
			else
			{
				switch(permissionLevel)
				{
					case Access:
						errorMessage = claim.allowAccess(player);
						break;
					case Inventory:
						errorMessage = claim.allowContainers(player);
						break;
					default:
						errorMessage = claim.allowBuild(player, Material.AIR);					
				}
			}
			
			//error message for trying to grant a permission the player doesn't have
			if(errorMessage != null)
			{
				GriefPrevention.sendMessage(player, TextMode.Err, Messages.CantGrantThatPermission);
				return;
			}
			
			targetClaims.add(claim);
		}
		
		//if we didn't determine which claims to modify, tell the player to be specific
		if(targetClaims.size() == 0)
		{
			GriefPrevention.sendMessage(player, TextMode.Err, Messages.GrantPermissionNoClaim);
			return;
		}
		
		//apply changes
		for(int i = 0; i < targetClaims.size(); i++)
		{
			Claim currentClaim = targetClaims.get(i);
			String identifierToAdd = recipientName;
			if(permission != null)
			{
			    identifierToAdd = "[" + permission + "]";
			}
			else if(recipientID != null)
			{
			    identifierToAdd = recipientID.toString(); 
			}
			
			if(permissionLevel == null)
			{
				if(!currentClaim.managers.contains(identifierToAdd))
				{
					currentClaim.managers.add(identifierToAdd);
				}
			}
			else
			{				
			    currentClaim.setPermission(identifierToAdd, permissionLevel);
			}
			this.dataStore.saveClaim(currentClaim);
		}
		
		//notify player
		if(recipientName.equals("public")) recipientName = this.dataStore.getMessage(Messages.CollectivePublic);
		String permissionDescription;
		if(permissionLevel == null)
		{
			permissionDescription = this.dataStore.getMessage(Messages.PermissionsPermission);
		}
		else if(permissionLevel == ClaimPermission.Build)
		{
			permissionDescription = this.dataStore.getMessage(Messages.BuildPermission);
		}		
		else if(permissionLevel == ClaimPermission.Access)
		{
			permissionDescription = this.dataStore.getMessage(Messages.AccessPermission);
		}
		else //ClaimPermission.Inventory
		{
			permissionDescription = this.dataStore.getMessage(Messages.ContainersPermission);
		}
		
		String location;
		if(claim == null)
		{
			location = this.dataStore.getMessage(Messages.LocationAllClaims);
		}
		else
		{
			location = this.dataStore.getMessage(Messages.LocationCurrentClaim);
		}
		
		GriefPrevention.sendMessage(player, TextMode.Success, Messages.GrantPermissionConfirmation, recipientName, permissionDescription, location);
	}

	
	private OfflinePlayer resolvePlayerByName(String name) 
	{
		//try online players first
		Player targetPlayer = this.getServer().getPlayerExact(name);
		if(targetPlayer != null) return targetPlayer;
		
		targetPlayer = this.getServer().getPlayer(name);
        if(targetPlayer != null) return targetPlayer;
      
		return this.getServer().getOfflinePlayer(name);
	}

	public void onDisable()
	{ 
		//save data for any online players
		Player[] players = this.getServer().getOnlinePlayers();
		for(Player player : players)
		{
			String playerID = player.getName();
			PlayerData playerData = this.dataStore.getPlayerData(playerID);
			this.dataStore.savePlayerDataSync(playerID, playerData);
		}
		
		this.dataStore.close();
		
		//dump any remaining unwritten log entries
		this.customLogger.WriteEntries();
		
		AddLogEntry("GriefPrevention disabled.");
	}
	
	//called when a player spawns, applies protection for that player if necessary

	static boolean isInventoryEmpty(Player player)
	{
	    PlayerInventory inventory = player.getInventory();
        ItemStack [] armorStacks = inventory.getArmorContents();
        
        //check armor slots, stop if any items are found
        for(int i = 0; i < armorStacks.length; i++)
        {
            if(!(armorStacks[i] == null || armorStacks[i].getType() == Material.AIR)) return false;
        }
        
        //check other slots, stop if any items are found
        ItemStack [] generalStacks = inventory.getContents();
        for(int i = 0; i < generalStacks.length; i++)
        {
            if(!(generalStacks[i] == null || generalStacks[i].getType() == Material.AIR)) return false;
        }
        
	    return true;
    }
	
	//moves a player from the claim he's in to a nearby wilderness location
	public Location ejectPlayer(Player player)
	{
		//look for a suitable location
		Location candidateLocation = player.getLocation();
		while(true)
		{
			Claim claim = null;
			claim = GriefPrevention.instance.dataStore.getClaimAt(candidateLocation, false, null);
			
			//if there's a claim here, keep looking
			if(claim != null)
			{
				candidateLocation = new Location(claim.lesserBoundaryCorner.getWorld(), claim.lesserBoundaryCorner.getBlockX() - 1, claim.lesserBoundaryCorner.getBlockY(), claim.lesserBoundaryCorner.getBlockZ() - 1);
				continue;
			}
			
			//otherwise find a safe place to teleport the player
			else
			{
				//find a safe height, a couple of blocks above the surface
				GuaranteeChunkLoaded(candidateLocation);
				Block highestBlock = candidateLocation.getWorld().getHighestBlockAt(candidateLocation.getBlockX(), candidateLocation.getBlockZ());
				Location destination = new Location(highestBlock.getWorld(), highestBlock.getX(), highestBlock.getY() + 2, highestBlock.getZ());
				player.teleport(destination);			
				return destination;
			}			
		}
	}
	
	//ensures a piece of the managed world is loaded into server memory
	//(generates the chunk if necessary)
	private static void GuaranteeChunkLoaded(Location location)
	{
		Chunk chunk = location.getChunk();
		while(!chunk.isLoaded() || !chunk.load(true));
	}
	
	//sends a color-coded message to a player
	static void sendMessage(Player player, ChatColor color, Messages messageID, String... args)
	{
		sendMessage(player, color, messageID, 0, args);
	}
	
	//sends a color-coded message to a player
	static void sendMessage(Player player, ChatColor color, Messages messageID, long delayInTicks, String... args)
	{
		String message = GriefPrevention.instance.dataStore.getMessage(messageID, args);
		sendMessage(player, color, message, delayInTicks);
	}
	
	//sends a color-coded message to a player
	static void sendMessage(Player player, ChatColor color, String message)
	{
		if(message == null || message.length() == 0) return;
		
	    if(player == null)
		{
			GriefPrevention.AddLogEntry(color + message);
		}
		else
		{
			player.sendMessage(color + message);
		}
	}
	
	static void sendMessage(Player player, ChatColor color, String message, long delayInTicks)
	{
		SendPlayerMessageTask task = new SendPlayerMessageTask(player, color, message);
		if(delayInTicks > 0)
		{
			GriefPrevention.instance.getServer().getScheduler().runTaskLater(GriefPrevention.instance, task, delayInTicks);
		}
		else
		{
			task.run();
		}
	}
	
	//checks whether players can create claims in a world
    public boolean claimsEnabledForWorld(World world)
    {
        return this.config_claims_worldModes.get(world) != ClaimsMode.Disabled;
    }
    
    //determines whether creative anti-grief rules apply at a location
	boolean creativeRulesApply(Location location)
	{
		return this.config_claims_worldModes.get((location.getWorld())) == ClaimsMode.Creative;
	}
	
	public String allowBuild(Player player, Location location)
	{
	    return this.allowBuild(player, location, location.getBlock().getType());
	}
	
	public String allowBuild(Player player, Location location, Material material)
	{
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		Claim claim = this.dataStore.getClaimAt(location, false, playerData.lastClaim);
		
		//exception: administrators in ignore claims mode and special player accounts created by server mods
		if(playerData.ignoreClaims) return null;
		
		//wilderness rules
		if(claim == null)
		{
			//no building in the wilderness in creative mode
			if(this.creativeRulesApply(location) || this.config_claims_worldModes.get(location.getWorld()) == ClaimsMode.SurvivalRequiringClaims)
			{
				//exception: when chest claims are enabled, players who have zero land claims and are placing a chest
			    if(material != Material.CHEST || playerData.getClaims().size() > 0 || GriefPrevention.instance.config_claims_automaticClaimsForNewPlayersRadius == -1)
			    {
    			    String reason = this.dataStore.getMessage(Messages.NoBuildOutsideClaims);
    				if(player.hasPermission("griefprevention.ignoreclaims"))
    					reason += "  " + this.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
    				reason += "  " + this.dataStore.getMessage(Messages.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
    				return reason;
			    }
			    else
			    {
			        return null;
			    }
			}
			
		    //but it's fine in survival mode
		    else
			{
				return null;
			}			
		}
		
		//if not in the wilderness, then apply claim rules (permissions, etc)
		else
		{
			//cache the claim for later reference
			playerData.lastClaim = claim;
			return claim.allowBuild(player, material);
		}
	}
	
	public String allowBreak(Player player, Block block, Location location)
	{
		PlayerData playerData = this.dataStore.getPlayerData(player.getName());
		Claim claim = this.dataStore.getClaimAt(location, false, playerData.lastClaim);
		
		//exception: administrators in ignore claims mode, and special player accounts created by server mods
		if(playerData.ignoreClaims) return null;
		
		//wilderness rules
		if(claim == null)
		{
			//no building in the wilderness in creative mode
			if(this.creativeRulesApply(location) || this.config_claims_worldModes.get(location.getWorld()) == ClaimsMode.SurvivalRequiringClaims)
			{
				String reason = this.dataStore.getMessage(Messages.NoBuildOutsideClaims);
				if(player.hasPermission("griefprevention.ignoreclaims"))
					reason += "  " + this.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
				reason += "  " + this.dataStore.getMessage(Messages.CreativeBasicsVideo2, DataStore.CREATIVE_VIDEO_URL);
				return reason;
			}
			
			//but it's fine in survival mode
			else
			{
				return null;
			}
		}
		else
		{
			//cache the claim for later reference
			playerData.lastClaim = claim;
		
			//if not in the wilderness, then apply claim rules (permissions, etc)
			return claim.allowBreak(player, block.getType());
		}
	}

	//restores nature in multiple chunks, as described by a claim instance
	//this restores all chunks which have ANY number of claim blocks from this claim in them
	//if the claim is still active (in the data store), then the claimed blocks will not be changed (only the area bordering the claim)
	public void restoreClaim(Claim claim, long delayInTicks)
	{
		//admin claims aren't automatically cleaned up when deleted or abandoned
		if(claim.isAdminClaim()) return;
		
		//it's too expensive to do this for huge claims
		if(claim.getArea() > 10000) return;
		
		ArrayList<Chunk> chunks = claim.getChunks();
        for(Chunk chunk : chunks)
        {
			this.restoreChunk(chunk, this.getSeaLevel(chunk.getWorld()) - 15, false, delayInTicks, null);
        }
	}
	
	public void restoreChunk(Chunk chunk, int miny, boolean aggressiveMode, long delayInTicks, Player playerReceivingVisualization)
	{
		//build a snapshot of this chunk, including 1 block boundary outside of the chunk all the way around
		int maxHeight = chunk.getWorld().getMaxHeight();
		BlockSnapshot[][][] snapshots = new BlockSnapshot[18][maxHeight][18];
		Block startBlock = chunk.getBlock(0, 0, 0);
		Location startLocation = new Location(chunk.getWorld(), startBlock.getX() - 1, 0, startBlock.getZ() - 1);
		for(int x = 0; x < snapshots.length; x++)
		{
			for(int z = 0; z < snapshots[0][0].length; z++)
			{
				for(int y = 0; y < snapshots[0].length; y++)
				{
					Block block = chunk.getWorld().getBlockAt(startLocation.getBlockX() + x, startLocation.getBlockY() + y, startLocation.getBlockZ() + z);
					snapshots[x][y][z] = new BlockSnapshot(block.getLocation(), block.getTypeId(), block.getData());
				}
			}
		}
		
		chunk.getBlock(0,  0, 0).getLocation();
		chunk.getBlock(15, 0, 15).getLocation();
	}
	
	private void parseMaterialListFromConfig(List<String> stringsToParse, MaterialCollection materialCollection)
	{
		materialCollection.clear();
		
		//for each string in the list
		for(int i = 0; i < stringsToParse.size(); i++)
		{
			//try to parse the string value into a material info
			MaterialInfo materialInfo = MaterialInfo.fromString(stringsToParse.get(i));
			
			//null value returned indicates an error parsing the string from the config file
			if(materialInfo == null)
			{
				//show error in log
				GriefPrevention.AddLogEntry("ERROR: Unable to read a material entry from the config file.  Please update your config.yml.");
				
				//update string, which will go out to config file to help user find the error entry
				if(!stringsToParse.get(i).contains("can't"))
				{
					stringsToParse.set(i, stringsToParse.get(i) + "     <-- can't understand this entry, see BukkitDev documentation");
				}
			}
			
			//otherwise store the valid entry in config data
			else
			{
				materialCollection.Add(materialInfo);
			}
		}		
	}
	
	public int getSeaLevel(World world)
	{
		Integer overrideValue = this.config_seaLevelOverride.get(world.getName());
		if(overrideValue == null || overrideValue == -1)
		{
			return world.getSeaLevel();
		}
		else
		{
			return overrideValue;
		}		
	}
	
	private static Block getTargetNonAirBlock(Player player, int maxDistance) throws IllegalStateException
    {
        BlockIterator iterator = new BlockIterator(player.getLocation(), player.getEyeHeight(), maxDistance);
        Block result = player.getLocation().getBlock().getRelative(BlockFace.UP);
        while (iterator.hasNext())
        {
            result = iterator.next();
            if(result.getType() != Material.AIR) return result;
        }
        
        return result;
    }
}