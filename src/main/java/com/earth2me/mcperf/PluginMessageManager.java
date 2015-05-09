package com.earth2me.mcperf;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
//import io.netty.buffer.ByteBuf;
//import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.messaging.PluginMessageRecipient;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@RequiredArgsConstructor
public class PluginMessageManager implements PluginMessageListener, Listener
{
	private final static long RECHECK_DELAY = 2 * 20;

	private final static Map<String, String> channelDescriptions;

	private final Server server;
	private final Logger logger;
	private final Plugin plugin;

	//private WeakHashMap<Player, FmlInfo> fmls;

	static
	{
		Map<String, String> _channelDescriptions = new HashMap<>();
		_channelDescriptions.put("FORGE", "Forge");
		_channelDescriptions.put("FML", "Forge Mod Loader");
		_channelDescriptions.put("WECUI", "WorldEdit CUI");
		channelDescriptions = Collections.unmodifiableMap(_channelDescriptions);
	}

	private long getRecheckDelay()
	{
		return RECHECK_DELAY;
	}

	public void register()
	{
		//fmls = new WeakHashMap<>();

		registerDuplex("FML");
		registerDuplex("FML|HS");
		registerDuplex("FML|MP");

		server.getPluginCommand("chans").setExecutor(this::onChansCommand);
	}

	public void unregister()
	{
		Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
		Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin);

		/*
		if (fmls != null)
		{
			fmls.clear();
			fmls = null;
		}
		*/
	}

	private void registerRx(String channel)
	{
		Bukkit.getMessenger().registerIncomingPluginChannel(plugin, channel, this);
	}

	private void registerTx(String channel)
	{
		Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
	}

	private void registerDuplex(String channel)
	{
		registerRx(channel);
		registerTx(channel);
	}

	@SuppressWarnings("deprecation")
	private boolean onChansCommand(final CommandSender sender, Command command, String label, String[] args)
	{
		boolean hasAllPermissions = sender.isOp() || sender.hasPermission("mcperf.chans.*");

		if (!hasAllPermissions && !sender.hasPermission("mcperf.chans"))
		{
			return Util.denyPermission(sender);
		}

		if (args.length < 1)
		{
			return false;
		}

		if (args.length > 1 && !hasAllPermissions && !sender.hasPermission("mcperf.chans.multiple"))
		{
			return Util.denyPermission(sender);
		}

		Stream<Player> players;
		if (args.length == 1 && "*".equals(args[0]))
		{
			if (!hasAllPermissions && !sender.hasPermission("mcperf.chans.all"))
			{
				return Util.denyPermission(sender);
			}

			players = server.getOnlinePlayers().stream().map(p -> p);  // Gets around buggy generics
		}
		else
		{
			players = Stream.of(args)
				.map(server::getPlayer)
				.filter(java.util.Objects::nonNull);
		}

		Map<Player, Set<String>> playerChannels = players.collect(Collectors.toMap(Function.identity(), PluginMessageRecipient::getListeningPluginChannels));

		if (playerChannels.isEmpty())
		{
			sender.sendMessage("No online players matched your parameters.");
			return true;
		}

		List<Player> notListening = playerChannels.entrySet().stream()
			.filter(e -> e.getValue().isEmpty())
			.map(Map.Entry::getKey)
			.collect(Collectors.toList());

		if (notListening.isEmpty())
		{
			sender.sendMessage("All specified players have at least one channel registered.");
		}
		else
		{
			sender.sendMessage("None registered: " + Joiner.on(", ").join(notListening));
		}

		playerChannels.entrySet().stream()
			.filter(e -> !e.getValue().isEmpty())
			.forEach(e -> sender.sendMessage(String.format("- %s: %s", e.getKey().getName(), Joiner.on(", ").join(e.getValue()))));
		return true;
	}

	public boolean checkChannels(Player player)
	{
		Set<String> channels = player.getListeningPluginChannels();
		List<String> warnings = channelDescriptions.entrySet().stream()
			.filter(c -> channels.contains(c.getKey()))
			.map(Map.Entry::getValue)
			.collect(Collectors.toList());

		if (warnings.isEmpty())
		{
			debug("Player %s isn't using any mod channels at the moment.  Channels: %s", player.getName(), Joiner.on(", ").join(player.getListeningPluginChannels()));
			return false;
		}

		alert("Warning: Player %s is using mod channels: %s", player.getName(), Joiner.on(", ").join(warnings));
		return true;
	}

	public void alert(String format, Object... args)
	{
		alert(String.format(format, args));
	}

	public void alert(String message)
	{
		Stream.concat(
			Stream.of(server.getConsoleSender()),
			server.getOperators().stream()
				.filter(p -> p instanceof Player)
				.map(p -> (CommandSender)p)
		).forEach(s -> s.sendMessage(message));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
	public void onPlayerQuit(PlayerQuitEvent event)
	{
		//fmls.remove(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
	public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event)
	{
		Player player = event.getPlayer();
		String channel = event.getChannel();

		alert("[CHANSNIFF] %s registered %s", player.getName(), channel);

		switch (channel)
		{
			case "FML":  // Broken at the moment
				/*
				player.sendPluginMessage(plugin, channel, new byte[] {
					(byte)FmlPacketType.INIT_HANDSHAKE.ordinal(),
					(byte)FmlSide.CLIENT.ordinal(),
				});
				*/
				break;
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
	public void onPlayerUnregisterChannel(PlayerUnregisterChannelEvent event)
	{
		Player player = event.getPlayer();
		String channel = event.getChannel();

		debug("[CHANSNIFF] %s unregistered %s", player.getName(), channel);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] data)
	{
		debug("[CHANSNIFF:D] Received data from %s on channel %s", player.getName(), channel);

		/*
		try
		{
			switch (channel)
			{
				case "FML":
					onReceivedFml(player, channel,data);
					break;
				case "FML|HS":
					onReceivedFmlHs(player, channel, data);
					break;
				case "FML|MP":
					onReceivedFmlMp(player, channel,data);
					break;
			}
		}
		catch (Exception ex)
		{
			logger.warning(String.format("Got bad plugin message from player %s on channel %s: %s", player.getName(), channel, ex.getMessage()));
		}
		*/
	}

	private void debug(String format, Object... args)
	{
		server.getConsoleSender().sendMessage(String.format(format, args));
	}

	/*

	private void onModsFound(Player player, Map<String, String> mods)
	{
		alert(
			"[MODSNIFF] Found mods for player %s: %s",
			player.getName(),
			Joiner.on(", ").join(mods.entrySet().stream()
				.map(e -> String.format("%s=%s", e.getKey(), e.getValue()))
				.collect(Collectors.toList()))
		);
	}

	private FmlInfo getFml(Player player)
	{

		FmlInfo fml;

		if (!fmls.containsKey(player))
		{
			fmls.put(player, fml = new FmlInfo());
		}
		else
		{
			fml = fmls.get(player);

			if (fml == null)
			{
				fmls.put(player, fml = new FmlInfo());
			}
		}

		return fml;
	}

	private static int readFmlVarInt(ByteBuf buffer, int max)
	{
		assert max > 0: "FML doesn't support zero-length varints";
		assert max <= 5: "FML doesn't support varints greater than 5 iterations";

		int value = 0;

		for (byte b, i = 0; ((b = buffer.readByte()) >>> 7) == 1; i++)
		{
			if (i >= max)
			{
				throw new ArithmeticException("FML varint was too large");
			}

			value |= (b & 0x7F) << i * 7;
		}

		return value;
	}

	private static String readFmlString(ByteBuf buffer)
	{
		int length = readFmlVarInt(buffer, 2);
		int index = buffer.readerIndex();

		try
		{
			return buffer.toString(index, length, Charsets.UTF_8);
		}
		finally
		{
			buffer.readerIndex(index + length);
		}
	}

	@SuppressWarnings("UnusedParameters")
	private void onReceivedFml(Player player, String channel, byte[] data)
	{
		// Not implemented yet.
	}

	@SuppressWarnings("UnusedParameters")
	private void onReceivedFmlMp(Player player, String channel, byte[] data)
	{
		// Not implemented yet.
	}

	@SuppressWarnings("deprecation")
	private void onReceivedFmlHs(Player player, String channel, byte[] data)
	{
		FmlInfo fml = getFml(player);
		ByteBuf rx = Unpooled.unmodifiableBuffer(Unpooled.wrappedBuffer(data));
		ByteBuf tx = Unpooled.buffer();

		FmlHsStage stage = Objects.firstNonNull(fml.hsStage, FmlHsStage.HELLO);
		debug("[CHANSNIFF:D] channel %s stage %s player %s", channel, stage.name(), player.getName());

		switch (stage)
		{
			case START:
				try
				{
					fml.protocol = 4;
					fml.dimension = player.getWorld().getEnvironment().getId();

					FmlHsPacketType.SERVER_HELLO.begin(tx);
					tx.writeByte(fml.protocol);
					tx.writeInt(fml.dimension);
				}
				finally
				{
					fml.hsStage = FmlHsStage.HELLO;
				}
				break;

			case HELLO:
				switch (FmlHsPacketType.identify(rx))
				{
					case CLIENT_HELLO:
						try
						{
							fml.protocol = rx.readByte();
						}
						finally
						{
							fml.hsStage = FmlHsStage.HELLO;  // Continue with this stage
						}
						break;

					case MOD_LIST:
						debug("################## Got mod list on channel %s", channel);
						try
						{
							fml.modCount = readFmlVarInt(rx, 2);
							HashMap<String, String> modTags = new HashMap<>(fml.modCount);
							for (int i = 0; i < fml.modCount; i++)
							{
								modTags.put(readFmlString(rx), readFmlString(rx));
							}
							fml.modTags = Collections.unmodifiableMap(modTags);

							FmlHsPacketType.MOD_LIST.begin(tx);
							tx.writeByte(0);  // Easy way of saying we have no mods.  Constitutes entire ModList packet.
						}
						finally
						{
							fml.hsStage = FmlHsStage.CLIENT_ACK;
						}
						break;

					default:
						fml.hsStage = FmlHsStage.ERROR;
						break;
				}
				break;

			case CLIENT_ACK:
				try
				{
					// RegistryData packets would go here.  Once they're finished, a HandshakeAck packet gets sent.
					FmlHsPacketType.ACK.begin(tx);
					tx.writeByte(fml.hsStage.ordinal());
				}
				finally
				{
					fml.hsStage = FmlHsStage.COMPLETE;
				}
				break;

			case COMPLETE:
				try
				{
					FmlHsPacketType.ACK.begin(tx);
					tx.writeByte(fml.hsStage.ordinal());
				}
				finally
				{
					fml.hsStage = FmlHsStage.DONE;
				}
				break;

			case DONE:
			case ERROR:
				return;
		}

		if (tx.writerIndex() > 0)
		{
			player.sendPluginMessage(plugin, channel, tx.array());
		}
	}


	private static class FmlInfo
	{
		public FmlHsStage hsStage = FmlHsStage.START;
		public byte protocol;
		public int dimension;
		public int modCount;
		public Map<String, String> modTags = Collections.emptyMap();
	}

	@SuppressWarnings("unused")
	private enum FmlSide
	{
		CLIENT,
		SERVER,
		;
	}

	@SuppressWarnings("unused")
	private enum FmlPacketType
	{
		INIT_HANDSHAKE,
		OPEN_GUI,
		ENTITY_SPAWN_MESSAGE,
		ENTITY_ADJUST_MESSAGE,
		;
	}

	@SuppressWarnings("unused")
	private enum FmlHsStage
	{
		START,
		HELLO,
		CLIENT_ACK,
		COMPLETE,
		DONE,
		ERROR,
		;
	}

	private enum FmlHsPacketType
	{
		UNKNOWN(null),
		SERVER_HELLO((byte)0x00),
		CLIENT_HELLO((byte)0x01),
		MOD_LIST((byte)0x02),
		REGISTRY_DATA((byte)0x03),
		ACK((byte)0xff),
		RESET((byte)0xfe),
		;

		public final Byte id;

		FmlHsPacketType(Byte id)
		{
			this.id = id;
		}

		public static FmlHsPacketType get(byte id)
		{
			for (FmlHsPacketType i : FmlHsPacketType.values())
			{
				if (i.id == id)
				{
					return i;
				}
			}

			return UNKNOWN;
		}

		public static FmlHsPacketType identify(ByteBuf rx)
		{
			return get(rx.readByte());
		}

		public void begin(ByteBuf tx)
		{
			tx.writeByte(id);
		}
	}*/
}
