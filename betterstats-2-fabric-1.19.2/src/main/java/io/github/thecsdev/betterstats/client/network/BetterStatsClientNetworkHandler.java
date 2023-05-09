package io.github.thecsdev.betterstats.client.network;

import static io.github.thecsdev.betterstats.BetterStats.LOGGER;
import static io.github.thecsdev.betterstats.client.gui_hud.screen.BetterStatsHudScreen.HUD_ID;
import static io.github.thecsdev.betterstats.network.BetterStatsNetworkHandler.C2S_PREFS;
import static io.github.thecsdev.betterstats.network.BetterStatsNetworkHandler.S2C_I_HAVE_BSS;
import static io.github.thecsdev.betterstats.network.BetterStatsNetworkHandler.S2C_REQ_PREFS;
import static io.github.thecsdev.tcdcommons.api.client.registry.TCDCommonsClientRegistry.InGameHud_Screens;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.NetworkManager.Side;
import io.github.thecsdev.betterstats.BetterStats;
import io.github.thecsdev.betterstats.client.gui_hud.screen.BetterStatsHudScreen;
import io.github.thecsdev.betterstats.network.BSNetworkProfile;
import io.github.thecsdev.tcdcommons.api.events.TNetworkEvent;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;

/**
 * Client-side network handler for {@link BetterStats}.
 */
public final class BetterStatsClientNetworkHandler
{
	// ==================================================
	public static boolean respondToPrefs;
	public static boolean serverHasBSS;
	private static final Cache<String, BSNetworkProfile> ProfileCache;
	// ==================================================
	protected BetterStatsClientNetworkHandler() {}
	public static void init() {/*calls static*/}
	// ==================================================
	static
	{
		//init variables
		ProfileCache = CacheBuilder.newBuilder()
				.expireAfterWrite(1, TimeUnit.MINUTES)
				.build();
		
		//init network
		initNetworkReceivers();
	}
	
	private static void initNetworkReceivers()
	{
		//by default, do not respond to S2C_REQ_PREFS
		respondToPrefs = false;
		serverHasBSS = false;
		ClientPlayerEvent.CLIENT_PLAYER_QUIT.register((cp) ->
		{
			respondToPrefs = false;
			serverHasBSS = false;
			InGameHud_Screens.remove(HUD_ID); //TODO - temporary bug fix for switching worlds/servers
		});
		//handle S2C_REQ_PREFS
		NetworkManager.registerReceiver(Side.S2C, S2C_I_HAVE_BSS, (payload, context) -> serverHasBSS = true);
		NetworkManager.registerReceiver(Side.S2C, S2C_REQ_PREFS, (payload, context) -> c2s_sendPrefs());
		
		//handle receiving stats
		TNetworkEvent.RECEIVE_PACKET_POST.register((packet, side) ->
		{
			if(!(packet instanceof StatisticsS2CPacket) || side != NetworkSide.CLIENTBOUND)
				return;
			onReceivedBSNetworkProfile(BSNetworkProfile.ofLocalClient());
		});
	}
	
	private static boolean onReceivedBSNetworkProfile(BSNetworkProfile profile)
	{
		//null check, return false to indicate failure
		if(profile == null) return false;
		
		//obtain profile info
		var pDisplayName = profile.getProfileDisplayName();
		var existingProfile = ProfileCache.getIfPresent(pDisplayName);
		//var notifyCurrentScreen = (existingProfile == null) || profile.isLocalClient();
		
		//cache...
		if(existingProfile != null)
			//if one exists, just add the updated stats on top of it
			existingProfile.putAllStats(profile.stats);
		else
			//but if one doesn't exist, then put this new one in place
			ProfileCache.put(pDisplayName, (existingProfile = profile));
		
		//...and notify
		var client = MinecraftClient.getInstance();
		var screen = client.currentScreen;
		if(screen instanceof BStatsListener)
		{
			var bsl = (BStatsListener)screen;
			if(Objects.equals(bsl.getListenerTargetGameProfile(), profile.gameProfile))
				client.executeSync(() -> bsl.onStatsReady(profile)); //TODO - Thread safety!
		}
		
		//return true to indicate everything was done
		return true;
	}
	// ==================================================
	public static boolean comms() { return (respondToPrefs || MinecraftClient.getInstance().isInSingleplayer()); }
	public static boolean c2s_sendPrefs()
	{
		//only send C2S_PREFS when allowed to
		if(!comms()) return false;
		
		//create prefs. packet
		var data = new PacketByteBuf(Unpooled.buffer());
		data.writeBoolean(respondToPrefs && BetterStatsHudScreen.getInstance() != null); //boolean - statsHudAccuracyMode
		var packet = new CustomPayloadC2SPacket(C2S_PREFS, data);
		//send packet
		try { MinecraftClient.getInstance().getNetworkHandler().sendPacket(packet); }
		catch(Exception e) { LOGGER.debug("Failed to send '" + C2S_PREFS + "' packet; " + e.getMessage()); }
		//return
		return true;
	}
	// ==================================================
}