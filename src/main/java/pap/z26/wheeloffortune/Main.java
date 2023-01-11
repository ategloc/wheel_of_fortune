package pap.z26.wheeloffortune;

public class Main {

    public static void main(String[] args) {
        if(args.length > 0 && args[0].equals("server")) {
            int listenPort = 56969;
            if(args.length >= 3 && args[1].equals("-port")) {
                try {
                    listenPort = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {}
            }
            GameServer onlyServer = new GameServer(listenPort, true);
            System.out.println("Started server on port " + listenPort + ". To change the port, add option -port <port number>");
        } else {
            WheelOfFortune game = new WheelOfFortune();
            NetworkClient networkClient = new NetworkClient("localhost", 26969, game);
            game.setNetworkClient(networkClient);
            GameServer localServer = new GameServer(26969);
            networkClient.start();
            game.loginToGameServer();
        }
    }
}
