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
                    // Clean up any AceVik the Victorious cards in library/hand so none remain in hand or deck
                    for (forge.game.card.Card c : new java.util.ArrayList<>(p.getCardsIn(forge.game.zone.ZoneType.Library))) {
                        if ("AceVik the Victorious".equals(c.getName())) {
                            if (c.getZone() != null) { c.getZone().remove(c); c.setZone(null); }
                        }
                    }
                    for (forge.game.card.Card c : new java.util.ArrayList<>(p.getCardsIn(forge.game.zone.ZoneType.Hand))) {
                        if ("AceVik the Victorious".equals(c.getName())) {
                            if (c.getZone() != null) { c.getZone().remove(c); c.setZone(null); }
                        }
                    }

                    forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getUniqueByName("AceVik the Victorious");
                    if (pc == null) {
                        pc = forge.StaticData.instance().getCommonCards().getCard("AceVik the Victorious");
                    }
                    if (pc != null) {
                        forge.game.card.Card card = forge.game.card.Card.fromPaperCard(pc, p);
                        game.getAction().moveToPlay(card, p, null, null);
                        card.setCounters(forge.game.card.CounterEnumType.LOYALTY, 4);
                    }
                    forge.game.card.Card cardWeb = null;
                    forge.item.PaperCard pcWeb = forge.StaticData.instance().getCommonCards().getUniqueByName("Friendship Web");
                    if (pcWeb != null) {
                        cardWeb = forge.game.card.Card.fromPaperCard(pcWeb, p);
                        game.getAction().moveToPlay(cardWeb, p, null, null);
                    }

                    Player opp = null;
                    for (Player other : game.getPlayers()) {
                        if (!other.equals(p)) {
                            opp = other;
                            break;
                        }
                    }
                    boolean isOppCommander = false;
                    if (opp != null) {
                        if (!opp.getCommanders().isEmpty()) {
                            isOppCommander = true;
                        } else if (opp.getRegisteredPlayer() != null && opp.getRegisteredPlayer().getCommanders() != null && !opp.getRegisteredPlayer().getCommanders().isEmpty()) {
                            isOppCommander = true;
                        } else if (opp.getRegisteredPlayer() != null && opp.getRegisteredPlayer().getDeck() != null && opp.getRegisteredPlayer().getDeck().has(forge.deck.DeckSection.Commander)) {
                            isOppCommander = true;
                        }
                    }
                    if (game.getRules().hasAppliedVariant(forge.game.GameType.Commander) || game.getRules().hasAppliedVariant(forge.game.GameType.Brawl)) {
                        isOppCommander = true;
                    }

                    int oppDeckSize = opp != null ? opp.getCardsIn(forge.game.zone.ZoneType.Library).size() + opp.getCardsIn(forge.game.zone.ZoneType.Hand).size() : 60;
                    boolean isOppCommanderOr99 = isOppCommander || (oppDeckSize >= 99);

                    if (isOppCommanderOr99) {
                        // 1. Set Friendship Web (already on battlefield) as Commander for AceVik without creating a duplicate in Command Zone
                        if (cardWeb != null) {
                            cardWeb.setCommander(true);
                            p.addCommander(cardWeb);
                        }

                        // 2. Ensure AceVik NEVER has Karakas in Commander mode (remove all Karakas cards from library/hand/deck)
                        for (forge.game.card.Card c : new java.util.ArrayList<>(p.getCardsIn(forge.game.zone.ZoneType.Library))) {
                            if ("Karakas".equalsIgnoreCase(c.getName())) {
                                if (c.getZone() != null) { c.getZone().remove(c); c.setZone(null); }
                            }
                        }
                        for (forge.game.card.Card c : new java.util.ArrayList<>(p.getCardsIn(forge.game.zone.ZoneType.Hand))) {
                            if ("Karakas".equalsIgnoreCase(c.getName())) {
                                if (c.getZone() != null) { c.getZone().remove(c); c.setZone(null); }
                            }
                        }
                        for (forge.game.card.Card c : new java.util.ArrayList<>(p.getCardsIn(forge.game.zone.ZoneType.Command))) {
                            if ("Karakas".equalsIgnoreCase(c.getName())) {
                                if (c.getZone() != null) { c.getZone().remove(c); c.setZone(null); }
                            }
                        }
                    } else {
                        // For standard decks (<99 cards), spawn Smothering Tithe on the battlefield at game start
                        forge.item.PaperCard pcTithe = forge.StaticData.instance().getCommonCards().getUniqueByName("Smothering Tithe");
                        if (pcTithe != null) {
                            forge.game.card.Card titheCard = forge.game.card.Card.fromPaperCard(pcTithe, p);
                            game.getAction().moveToPlay(titheCard, p, null, null);
                        }
                    }

                    // Set starting life to 128 (2^7)
                    p.setStartingLife(128);
                    p.setLife(128, null);

                    // Spawn only Raffine's Tower (untapped normally, tapped if Commander or deck >= 99 cards)
                    spawnLand(p, "Raffine's Tower", !isOppCommanderOr99);
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

    private void spawnLand(Player p, String landName, boolean untapped) {
        forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getUniqueByName(landName);
        if (pc != null) {
            forge.game.card.Card card = forge.game.card.Card.fromPaperCard(pc, p);
            game.getAction().moveToPlay(card, p, null, null);
            card.setTapped(!untapped);
        }
    }
}