package com.example.tictactoe.controller;

import com.example.tictactoe.enumeration.GameState;
import com.example.tictactoe.model.TicTacToe;
import com.example.tictactoe.model.dto.JoinMessage;
import com.example.tictactoe.model.dto.PlayerMessage;
import com.example.tictactoe.model.dto.TicTacToeMessage;
import com.example.tictactoe.manager.TicTacToeManager;
import com.example.tictactoe.repository.TicTacToeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Date;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;


@Controller
public class MessageController {
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private TicTacToeRepository ticTacToeRepository;

    private final TicTacToeManager ticTacToeManager = new TicTacToeManager();

    
    @MessageMapping("/game.join")
    @SendTo("/topic/game.state")
    public Object joinGame(@Payload JoinMessage message, SimpMessageHeaderAccessor headerAccessor) {
        TicTacToe game = ticTacToeManager.joinGame(message.getPlayer());
        if (game == null) {
            TicTacToeMessage errorMessage = new TicTacToeMessage();
            errorMessage.setType("error");
            errorMessage.setContent("Unable to enter the game. Perhaps the game is already full or an internal error has occurred.");
            return errorMessage;
        }
        Objects.requireNonNull(headerAccessor.getSessionAttributes()).put("gameId", game.getGameId());
        headerAccessor.getSessionAttributes().put("player", message.getPlayer());

        TicTacToeMessage gameMessage = gameToMessage(game);
        gameMessage.setType("game.joined");
        return gameMessage;
    }

   
    @MessageMapping("/game.leave")
    public void leaveGame(@Payload PlayerMessage message) {
        TicTacToe game = ticTacToeManager.leaveGame(message.getPlayer());
        if (game != null) {
            TicTacToeMessage gameMessage = gameToMessage(game);
            gameMessage.setType("game.left");
            messagingTemplate.convertAndSend("/topic/game." + game.getGameId(), gameMessage);
        }
    }

    
    @MessageMapping("/game.move")
    public void makeMove(@Payload TicTacToeMessage message) {
        String player = message.getSender();
        String gameId = message.getGameId();
        int move = message.getMove();
        TicTacToe game = ticTacToeManager.getGame(gameId);

        if (game == null || game.isGameOver()) {
            if (game == null) {
                TicTacToeMessage errorMessage = new TicTacToeMessage();
                errorMessage.setType("error");
                errorMessage.setContent("Game not found.");
                this.messagingTemplate.convertAndSend("/topic/game." + gameId, errorMessage);
            } else if (game.isGameOver()) {
                TicTacToeMessage errorMessage = new TicTacToeMessage();
                errorMessage.setType("error");
                errorMessage.setContent("Game is already over.");
                this.messagingTemplate.convertAndSend("/topic/game." + gameId, errorMessage);
            }
            return;
        }

        if (game.getGameState().equals(GameState.WAITING_FOR_PLAYER)) {
            TicTacToeMessage errorMessage = new TicTacToeMessage();
            errorMessage.setType("error");
            errorMessage.setContent("Game is waiting for another player to join.");
            this.messagingTemplate.convertAndSend("/topic/game." + gameId, errorMessage);
            return;
        }

        if (game.getTurn().equals(player)) {
            if (game.hasTimedOut(player)) {
                TicTacToeMessage errorMessage = new TicTacToeMessage();
                errorMessage.setType("error");
                errorMessage.setContent("Time has run out for your move.");
                this.messagingTemplate.convertAndSendToUser(player, "/queue/errors", errorMessage);
                return;
            }

            game.makeMove(player, move);

            TicTacToeMessage gameStateMessage = new TicTacToeMessage(game);
            gameStateMessage.setType("game.move");
            this.messagingTemplate.convertAndSend("/topic/game." + gameId, gameStateMessage);

            if (game.isGameOver()) {
                saveGameToDatabase(game);
                TicTacToeMessage gameOverMessage = gameToMessage(game);
                gameOverMessage.setType("game.gameOver");
                this.messagingTemplate.convertAndSend("/topic/game." + gameId, gameOverMessage);
                ticTacToeManager.removeGame(gameId);
            } else {
                game.startMoveTimer();

                scheduleTimeoutCheck(gameId, game.getTurn());
            }
        }
    }

    private void scheduleTimeoutCheck(String gameId, String currentTurn) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                TicTacToe game = ticTacToeManager.getGame(gameId);
                if (game != null && game.getTurn().equals(currentTurn)) {
                    game.startMoveTimer();  
                    String nextTurn = game.getTurn().equals(game.getPlayer1()) ? game.getPlayer2() : game.getPlayer1();
                    game.setTurn(nextTurn);

                    TicTacToeMessage timeoutMessage = new TicTacToeMessage(game);
                    timeoutMessage.setType("game.timeout");
                    messagingTemplate.convertAndSend("/topic/game." + gameId, timeoutMessage);

                    
                    scheduleTimeoutCheck(gameId, nextTurn);

                  
                    game.updateGameState();
                }
            }
        }, 30 * 1000);  
    }

   
    private void saveGameToDatabase(TicTacToe game) {
        TicTacToe savedGame = new TicTacToe();
        savedGame.setGameId(game.getGameId());
        savedGame.setBoard(game.getBoard());
        savedGame.setPlayer1(game.getPlayer1());
        savedGame.setPlayer2(game.getPlayer2());
        savedGame.setWinner(getPlayerName(game.getWinner(), game.getPlayer1(), game.getPlayer2()));
        savedGame.setStartTime(game.getStartTime());
        savedGame.setLastMoveTime(new Date());
        savedGame.setGameState(game.getGameState());

        ticTacToeRepository.save(savedGame);
    }

  
    private String getPlayerName(String mark, String player1, String player2) {
        if ("X".equals(mark)) {
            return player1;
        } else if ("O".equals(mark)) {
            return player2;
        }
        return "TIE";
    }

 
    @EventListener
    public void SessionDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());

        Object gameIdObject = headerAccessor.getSessionAttributes().get("gameId");
        Object playerObject = headerAccessor.getSessionAttributes().get("player");

   
        String gameId = (gameIdObject != null) ? gameIdObject.toString() : null;
        String player = (playerObject != null) ? playerObject.toString() : null;

        if (gameId != null && player != null) {
            TicTacToe game = ticTacToeManager.getGame(gameId);

            if (game != null) {
                handlePlayerDisconnect(game, player);
            }
        }
    }


    private void handlePlayerDisconnect(TicTacToe game, String player) {
        if (game.getPlayer1().equals(player)) {
            game.setPlayer1(null);
            if (game.getPlayer2() != null) {
                game.setGameState(GameState.PLAYER2_WON);
                game.setWinner(game.getPlayer2());
            } else {
                ticTacToeManager.removeGame(game.getGameId());
            }
        } else if (game.getPlayer2() != null && game.getPlayer2().equals(player)) {
            game.setPlayer2(null);
            if (game.getPlayer1() != null) {
                game.setGameState(GameState.PLAYER1_WON);
                game.setWinner(game.getPlayer1());
            } else {
                ticTacToeManager.removeGame(game.getGameId());
            }
        }

        TicTacToeMessage gameMessage = gameToMessage(game);
        gameMessage.setType("game.gameOver");
        messagingTemplate.convertAndSend("/topic/game." + game.getGameId(), gameMessage);
        ticTacToeManager.removeGame(game.getGameId());
    }

  
    private TicTacToeMessage gameToMessage(TicTacToe game) {
        TicTacToeMessage message = new TicTacToeMessage();
        message.setGameId(game.getGameId());
        message.setPlayer1(game.getPlayer1());
        message.setPlayer2(game.getPlayer2());
        message.setBoard(game.getBoard());
        message.setTurn(game.getTurn());
        message.setGameState(game.getGameState());
        message.setWinner(game.getWinner());
        return message;
    }
}
