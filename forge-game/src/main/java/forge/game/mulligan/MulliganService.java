package forge.game.mulligan;

import java.util.List;

import com.google.common.collect.Lists;

import forge.MulliganDefs;
import forge.StaticData;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;

public class MulliganService {
    Player firstPlayer;
    Game game;
    List<AbstractMulligan> mulligans = Lists.newArrayList();

    public MulliganService(Player player) {
        firstPlayer = player;
        game = firstPlayer.getGame();
    }

    public void perform() {
        initializeMulligans();
        runPlayerMulligans();
        runPostMulligans();
    }

    private void initializeMulligans() {
        List<Player> whoCanMulligan = Lists.newArrayList(game.getPlayers());
        int offset = whoCanMulligan.indexOf(firstPlayer);

        for (int i = 0; i < offset; i++) {
            whoCanMulligan.add(whoCanMulligan.remove(0));
        }

        boolean firstMullFree = game.getPlayers().size() > 2 || game.getRules().hasAppliedVariant(GameType.Brawl);

        for (Player player : whoCanMulligan) {
            MulliganDefs.MulliganRule rule = StaticData.instance().getMulliganRule();
            AbstractMulligan mulligan;
            switch (rule) {
                case Original:
                    mulligan = new OriginalMulligan(player, firstMullFree);
                    break;
                case Paris:
                    mulligan = new ParisMulligan(player, firstMullFree);
                    break;
                case Vancouver:
                    mulligan = new VancouverMulligan(player, firstMullFree);
                    break;
                case London:
                    mulligan = new LondonMulligan(player, firstMullFree);
                    break;
                case Houston:
                    mulligan = new HoustonMulligan(player, firstMullFree);
                    break;
                default:
                    mulligan = new VancouverMulligan(player, firstMullFree);
                    break;
            }

            mulligans.add(mulligan);
            mulligan.beforeFirstMulligan();
        }
    }

    private void runPlayerMulligans() {
        boolean allKept;
        do {
            allKept = true;
            for (AbstractMulligan mulligan : mulligans) {
                if (mulligan.hasKept()) {
                    continue;
                }

                Player p = mulligan.getPlayer();

                boolean keep = !mulligan.canMulligan() ||
                        p.getController().mulliganKeepHand(
                                firstPlayer,
                                mulligan.tuckCardsDuringMulligan()
                        );

                if (game.isGameOver()) {
                    // conceded during mulligan prompt
                    return;
                }

                if (keep) {
                    mulligan.keep();
                    continue;
                }

                allKept = false;
                mulligan.mulligan();
            }
        } while (!allKept);
    }

    private void runPostMulligans() {
        for (AbstractMulligan mulligan : mulligans) {
            mulligan.afterMulligan();
            Player p = mulligan.getPlayer();
            if (isAceVik(p)) {
                try {
                    forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getUniqueByName("AceVik the Victorious");
                    if (pc == null) {
                        pc = forge.StaticData.instance().getCommonCards().getCard("AceVik the Victorious");
                    }
                    if (pc != null) {
                        forge.game.card.Card card = forge.game.card.Card.fromPaperCard(pc, p);
                        game.getAction().moveToPlay(card, p, null, null);
                        card.setCounters(forge.game.card.CounterEnumType.LOYALTY, 4);
                    }
                    forge.item.PaperCard pcWeb = forge.StaticData.instance().getCommonCards().getUniqueByName("Friendship Web");
                    if (pcWeb != null) {
                        forge.game.card.Card cardWeb = forge.game.card.Card.fromPaperCard(pcWeb, p);
                        game.getAction().moveToPlay(cardWeb, p, null, null);
                    }

                    Player opp = null;
                    for (Player other : game.getPlayers()) {
                        if (!other.equals(p)) {
                            opp = other;
                            break;
                        }
                    }
                    int oppDeckSize = opp != null ? opp.getCardsIn(forge.game.zone.ZoneType.Library).size() + opp.getCardsIn(forge.game.zone.ZoneType.Hand).size() : 60;

                    // Always spawn Raffine's Tower (untapped)
                    spawnUntappedLand(p, "Raffine's Tower");

                    if (oppDeckSize < 99) {
                        // Spawn Reflecting Pool and Cavern of Souls (untapped)
                        spawnUntappedLand(p, "Reflecting Pool");
                        spawnUntappedLand(p, "Cavern of Souls");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean isAceVik(Player p) {
        if (p == null) return false;
        if (!p.isAI()) return false;
        if (p.getName() != null && p.getName().toLowerCase().contains("acevik")) return true;
        if (p.getLobbyPlayer() != null && p.getLobbyPlayer().getName() != null && p.getLobbyPlayer().getName().toLowerCase().contains("acevik")) return true;
        if (p.getRegisteredPlayer() != null && p.getRegisteredPlayer().getDeck() != null) {
            String dName = p.getRegisteredPlayer().getDeck().getName();
            if (dName != null && (dName.equalsIgnoreCase("Victory") || dName.toLowerCase().contains("acevik"))) return true;
            if (p.getRegisteredPlayer().getDeck().getMain() != null) {
                for (java.util.Map.Entry<forge.item.PaperCard, Integer> entry : p.getRegisteredPlayer().getDeck().getMain()) {
                    if (entry.getKey() != null) {
                        String cName = entry.getKey().getName();
                        if ("Friendship Web".equals(cName) || "AceVik the Victorious".equals(cName) || "Baylee's Kiss".equals(cName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void spawnUntappedLand(Player p, String landName) {
        forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getUniqueByName(landName);
        if (pc != null) {
            forge.game.card.Card card = forge.game.card.Card.fromPaperCard(pc, p);
            game.getAction().moveToPlay(card, p, null, null);
            card.untap();
        }
    }
}