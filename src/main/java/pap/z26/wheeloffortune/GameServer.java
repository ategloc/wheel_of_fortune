package pap.z26.wheeloffortune;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Game Server class, responds to requests from connected players and runs games
 */
public class GameServer {

    private NetworkClient networkClient;
    private final ArrayList<Game> games = new ArrayList<>();

    private record IpAndPort(InetAddress address, int port) {
    }

    private final HashMap<Player, IpAndPort> addresses = new HashMap<>();
    private final HashMap<String, Player> players = new HashMap<>();

    public GameServer(int listenPort, boolean outputLogs) {
        setup(listenPort, outputLogs);
    }

    public GameServer(int listenPort) {
        setup(listenPort, false);
    }

    /**
     * Sets up this server's {@link NetworkClient network client}
     *
     * @param listenPort number of the port that the client will be listening on
     * @param outputLogs if set to true, the client will output logs with received data in the console
     */
    private void setup(int listenPort, boolean outputLogs) {
        networkClient = new NetworkClient(listenPort, this);
        networkClient.start();
        if (outputLogs) networkClient.showLogs();
    }

    /**
     * Called by the {@link GameServer#networkClient network client}, receives the data sent by a connected game client
     * and executes actions based on the data
     *
     * @param data      JSON with data in a String form
     * @param ipAddress IP address from which the data came
     * @param port      number of port from which the data came
     */
    public void receiveDataFromClient(String data, InetAddress ipAddress, int port) {
        JSONObject jsonData = new JSONObject(data);
        String action = jsonData.getString("action");
        switch (action) {
            case "login" -> {
                JSONObject response = new JSONObject();
                response.put("action", "loginconf");
                response.put("message", login(jsonData, ipAddress, port));
                networkClient.sendData(response.toString(), ipAddress, port);
            }
            case "logout" -> {
                Player loggingOut = players.get(jsonData.getString("player"));
                if (loggingOut == null) return;
                if (loggingOut.getGame() != null) {
                    leaveGame(jsonData);
                }
                players.remove(jsonData.getString("player"));
                addresses.remove(loggingOut);
            }
            case "join" -> {
                JSONObject response = new JSONObject();
                response.put("action", "joinconf");
                Player joiningPlayer = players.get(jsonData.getString("player"));
                join(response, joiningPlayer);
                IpAndPort joiningPlayerAddress = addresses.get(joiningPlayer);
                networkClient.sendData(response.toString(), joiningPlayerAddress.address, joiningPlayerAddress.port);
                joinOth(joiningPlayer);
            }
            case "laj" -> {
                JSONObject response = new JSONObject();
                response.put("action", "lajconf");
                String loginResult = login(jsonData, ipAddress, port);
                response.put("message", loginResult);
                if (loginResult.equals("success")) {
                    Player joiningPlayer = players.get(jsonData.getString("player"));
                    join(response, joiningPlayer);
                    IpAndPort joiningPlayerAddress = addresses.get(joiningPlayer);
                    networkClient.sendData(response.toString(), joiningPlayerAddress.address, joiningPlayerAddress.port);
                    joinOth(joiningPlayer);
                }
            }
            case "leave" -> leaveGame(jsonData);
            case "start" -> {
                Player playerStarting = players.get(jsonData.getString("player"));
                Game gameToStart = playerStarting.getGame();
                if (gameToStart.isInProgress()) return;
                while (gameToStart.getPlayers().size() < 3) {
                    BotPlayer fillerPlayer = new BotPlayer();
                    while (!gameToStart.joinGame(fillerPlayer)) fillerPlayer = new BotPlayer();
                    JSONObject joinothResponse = new JSONObject();
                    joinothResponse.put("action", "joinoth");
                    joinothResponse.put("player", fillerPlayer.getName());
                    for (Player playerToNotify : gameToStart.getPlayers()) {
                        if (!playerToNotify.isBot()) {
                            IpAndPort alreadyInGamePlayerAddress = addresses.get(playerToNotify);
                            networkClient.sendData(joinothResponse.toString(), alreadyInGamePlayerAddress.address, alreadyInGamePlayerAddress.port);
                        }
                    }
                }
                for (Player inGamePlayer : gameToStart.getPlayers()) {
                    if (!inGamePlayer.isBot()) {
                        IpAndPort address = addresses.get(inGamePlayer);
                        networkClient.sendData(data, address.address, address.port);
                    }
                }
                gameToStart.startGame();
            }
            case "update" -> {
                JSONObject response = new JSONObject();

                Database db = Database.getInstance();

                ArrayList<Phrase> phrases = db.getAllPhrasesFromCategory(null);
                Map<String, String> hphrases = new HashMap<>();
                for (Phrase phrase : phrases) {
                    hphrases.put(phrase.phrase(), phrase.category());
                }

                ArrayList<LeaderboardRecord> leaderboard = db.getHighScores(null);
                Map<String, Integer> hrecords = new HashMap<>();
                for (LeaderboardRecord record : leaderboard) {
                    hrecords.put(record.playerName(), record.score());
                }

                JSONObject jphrases = new JSONObject(hphrases);
                JSONObject jrecords = new JSONObject(hrecords);

                response.put("action", "update");
                response.put("phrases", jphrases);
                response.put("leaderboard", jrecords);

                Player playerRequesting = players.get(jsonData.getString("player"));
                IpAndPort address = addresses.get(playerRequesting);
                networkClient.sendData(response.toString(), address.address, address.port);
            }
            default -> {
                Player playerStarting = players.get(jsonData.getString("player"));
                Game gameToStart = playerStarting.getGame();
                gameToStart.executeFromOutside(jsonData, true);
            }
        }
    }

    /**
     * Sends information about a particular action that happened in game to every game player besides the one from whom
     * the information came to the server
     *
     * @param data   JSON with data to be sent out
     * @param player {@link Player player} who will not be sent the data
     * @param game   {@link Game game} in which the action reported happened
     */
    public void tellEveryoneBut(String data, Player player, Game game) {
        for (Player gamePlayer : game.getPlayers()) {
            if (gamePlayer != player && !gamePlayer.isBot()) {
                IpAndPort address = addresses.get(gamePlayer);
                networkClient.sendData(data, address.address, address.port);
            }
        }
    }

    /**
     * Method that handles a player's request to leave the game they're currently in
     *
     * @param jsonData JSON with data containing the username of the player leaving their game
     */
    private void leaveGame(JSONObject jsonData) {
        Player leavingPlayer = players.get(jsonData.getString("player"));
        Game gameBeingLeft = leavingPlayer.getGame();
        JSONObject response = new JSONObject();
        response.put("action", "left");
        response.put("player", jsonData.getString("player"));
        if (gameBeingLeft.isInProgress()) {
            BotPlayer replacement = gameBeingLeft.leaveGameAndReplace(leavingPlayer);
            response.put("repl", replacement.getName());
        } else {
            gameBeingLeft.leaveGame(leavingPlayer);
        }
        boolean onlyBots = true;
        for (Player inGamePlayer : gameBeingLeft.getPlayers()) {
            if (!inGamePlayer.isBot()) {
                onlyBots = false;
                break;
            }
        }
        if (onlyBots) {
            gameBeingLeft.reset();
        } else {
            tellEveryoneBut(response.toString(), leavingPlayer, gameBeingLeft);
        }
    }

    /**
     * Method that handles a player's request to log in to this server
     *
     * @param jsonData  JSON with data containing player's username
     * @param ipAddress IP address of the player who sent this request
     * @param port      port of the player who sent this request
     * @return a text response with either an error like "Invalid user name" or "success" if the player was successfully
     * logged in
     */
    private String login(JSONObject jsonData, InetAddress ipAddress, int port) {
        for (Player player : addresses.keySet()) {
            if (player.getName().equals(jsonData.getString("player"))) {
                return "You are already logged in";
            }
        }
        if (jsonData.getString("player").equals("SYSTEM")) {
            return "Invalid user name";
        }
        HumanPlayer newPlayer = new HumanPlayer(jsonData.getString("player"));
        addresses.put(newPlayer, new IpAndPort(ipAddress, port));
        players.put(jsonData.getString("player"), newPlayer);
        return "success";
    }

    /**
     * Method that handles a player's request to join a game on this server
     *
     * @param response      part of the already generated response to this request
     * @param joiningPlayer {@link Player player} who is already logged in on this server who will be assigned a game
     */
    private void join(JSONObject response, Player joiningPlayer) {
        for (Game game : games) {
            if (game.getPlayers().size() < 3) {
                if (game.joinGame(joiningPlayer)) {
                    break;
                }
            }
        }
        if (joiningPlayer.getGame() == null) {
            Game newGame = new Game(this);
            games.add(newGame);
            newGame.joinGame(joiningPlayer);
        }
        JSONArray inGamePlayers = new JSONArray();
        for (Player inGamePlayer : joiningPlayer.getGame().getPlayers()) {
            inGamePlayers.put(inGamePlayer.getName());
        }
        response.put("players", inGamePlayers);
    }

    /**
     * Sends out a notice to every other player in a game that somebody just joined with a name of the joining player
     *
     * @param joiningPlayer {@link Player player} who just joined a game that other players will be notified about
     */
    private void joinOth(Player joiningPlayer) {
        JSONObject responseToOthers = new JSONObject();
        responseToOthers.put("action", "joinoth");
        responseToOthers.put("player", joiningPlayer.getName());
        for (Player inGamePlayer : joiningPlayer.getGame().getPlayers()) {
            if (inGamePlayer != joiningPlayer) {
                IpAndPort alreadyInGamePlayerAddress = addresses.get(inGamePlayer);
                networkClient.sendData(responseToOthers.toString(), alreadyInGamePlayerAddress.address, alreadyInGamePlayerAddress.port);
            }
        }
    }
}