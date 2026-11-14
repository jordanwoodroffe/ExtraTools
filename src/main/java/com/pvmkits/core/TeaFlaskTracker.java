package com.pvmkits.core;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the servings of tea (0-5) left in the Creature Creation tea flask. The
 * flask is a single item id whose contents are held server side (like a satchel),
 * so there is no varbit to read: the count is followed from the flask's chat
 * messages and its "Look-in" dialog, which gives the exact number. The value is
 * saved per account so it survives relogs and client restarts. Until the flask
 * has been looked in or filled to full once, the count is unknown (-1).
 */
@Singleton
public class TeaFlaskTracker {

    public static final int TEA_FLASK_ITEM_ID = ItemID.TOL_TEA;
    public static final int MAX_SERVINGS = 5;
    public static final int UNKNOWN = -1;

    private static final String CONFIG_GROUP = "pvmkits";
    private static final String CONFIG_KEY = "teaFlaskServings";

    // Chat messages from drinking and filling (Transcript:Tea flask on the wiki).
    private static final String DRINK_MESSAGE = "You take a drink from the flask...";
    private static final String NOW_EMPTY_MESSAGE = "The flask is now empty!";
    private static final String NOTHING_LEFT_MESSAGE = "There's nothing left in the flask.";
    private static final String ADD_TEA_MESSAGE = "You add the tea to the flask.";
    private static final String FULL_MESSAGE = "The flask is full!";

    // "Look-in" dialog text: "There is no tea in this flask.", "There is one
    // serving of tea in this flask." or "There are N servings of tea in this flask."
    private static final String LOOK_IN_EMPTY = "There is no tea in this flask.";
    private static final String LOOK_IN_ONE = "There is one serving of tea in this flask.";
    private static final Pattern LOOK_IN_MANY = Pattern.compile("There are (\\d+) servings of tea in this flask\\.");

    private final Client client;
    private final ClientThread clientThread;
    private final ConfigManager configManager;

    private int servings = UNKNOWN;
    private boolean loaded;

    // Empty cup / cup of tea counts from the last inventory snapshot, used to spot
    // the flask filling an empty cup (that action has no chat message).
    private int lastEmptyCups = -1;
    private int lastTeaCups = -1;

    @Inject
    public TeaFlaskTracker(Client client, ClientThread clientThread, ConfigManager configManager) {
        this.client = client;
        this.clientThread = clientThread;
        this.configManager = configManager;
    }

    /** Servings left in the flask (0-5), or {@link #UNKNOWN}. */
    public int getServings() {
        if (!loaded) {
            Integer saved = configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY, Integer.class);
            // The RS profile is only known once logged in; keep retrying until then.
            if (configManager.getRSProfileKey() != null) {
                servings = saved != null ? saved : UNKNOWN;
                loaded = true;
            }
        }
        return servings;
    }

    /** Drops the cached value so the next read reloads it (e.g. after switching account). */
    public void reset() {
        loaded = false;
        servings = UNKNOWN;
        lastEmptyCups = -1;
        lastTeaCups = -1;
    }

    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) {
            return;
        }

        String message = Text.removeTags(event.getMessage());
        int current = getServings();
        switch (message) {
            case DRINK_MESSAGE:
                if (current > 0) {
                    setServings(current - 1);
                }
                break;
            case NOW_EMPTY_MESSAGE:
            case NOTHING_LEFT_MESSAGE:
                setServings(0);
                break;
            case ADD_TEA_MESSAGE:
                if (current != UNKNOWN) {
                    setServings(Math.min(MAX_SERVINGS, current + 1));
                }
                break;
            case FULL_MESSAGE:
                setServings(MAX_SERVINGS);
                break;
            default:
                break;
        }
    }

    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != InterfaceID.OBJECTBOX) {
            return;
        }
        // The dialog text is filled in after the load event, so read it a frame later.
        clientThread.invokeLater(this::readLookInDialog);
    }

    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != InventoryID.INV) {
            return;
        }

        ItemContainer inventory = event.getItemContainer();
        int emptyCups = 0;
        int teaCups = 0;
        boolean hasFlask = false;
        for (Item item : inventory.getItems()) {
            if (item.getId() == ItemID.CUP_EMPTY) {
                emptyCups++;
            } else if (item.getId() == ItemID.CUP_OF_TEA) {
                teaCups++;
            } else if (item.getId() == TEA_FLASK_ITEM_ID) {
                hasFlask = true;
            }
        }

        // Using the flask on an empty cup pours one serving into it: exactly one
        // empty cup turns into one cup of tea, with no chat message to go on.
        int current = getServings();
        if (hasFlask && current > 0 && lastEmptyCups >= 0
                && emptyCups == lastEmptyCups - 1 && teaCups == lastTeaCups + 1) {
            setServings(current - 1);
        }

        lastEmptyCups = emptyCups;
        lastTeaCups = teaCups;
    }

    private void readLookInDialog() {
        Widget item = client.getWidget(InterfaceID.Objectbox.ITEM);
        Widget text = client.getWidget(InterfaceID.Objectbox.TEXT);
        if (item == null || text == null || item.getItemId() != TEA_FLASK_ITEM_ID) {
            return;
        }

        String dialog = Text.removeTags(text.getText().replace("<br>", " ")).replaceAll("\\s+", " ").trim();
        if (dialog.equals(LOOK_IN_EMPTY)) {
            setServings(0);
        } else if (dialog.equals(LOOK_IN_ONE)) {
            setServings(1);
        } else {
            Matcher matcher = LOOK_IN_MANY.matcher(dialog);
            if (matcher.matches()) {
                setServings(Math.min(MAX_SERVINGS, Integer.parseInt(matcher.group(1))));
            }
        }
    }

    private void setServings(int value) {
        servings = value;
        loaded = true;
        configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY, value);
    }
}
