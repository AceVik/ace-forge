/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.zone;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import forge.game.*;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.PlayEffect;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.event.*;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.mana.ManaRefundService;
import forge.game.player.Player;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.TargetChoices;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.util.IterableUtil;
import forge.util.TextUtil;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * <p>
 * MagicStack class.
 * </p>
 *
 * @author Forge
 * @version $Id$
 */
public class MagicStack /* extends MyObservable */ implements Iterable<SpellAbilityStackInstance> {
    private final List<SpellAbility> simultaneousStackEntryList = Lists.newArrayList();
    private final List<SpellAbility> activePlayerSAs = Lists.newArrayList();

    // They don't provide a LIFO queue, so had to use a deque
    private final Deque<SpellAbilityStackInstance> stack = new LinkedBlockingDeque<>();
    private final Stack<SpellAbilityStackInstance> frozenStack = new Stack<>();
    private final Stack<SpellAbility> undoStack = new Stack<>();
    private Player undoStackOwner;

    private SpellAbility primaryAbility = null;
    private boolean frozen = false;
    private boolean bResolving = false;

    private final List<SpellAbility> thisTurnCast = Lists.newArrayList();
    private List<Card> lastTurnCast = Lists.newArrayList();
    private final List<SpellAbility> thisTurnActivated = Lists.newArrayList();

    private Card curResolvingCard = null;

    private final Game game;
    private int lastTurnAceVikManaDrainUsed = -1;
    // deck lists and player names don't change during a game, so the (fairly expensive) boss deck
    // detection only has to run once per player instead of on every spell put onto the stack
    private final Map<Player, Boolean> aceVikCache = new IdentityHashMap<>(4);

    public MagicStack(Game gameState) {
        game = gameState;
    }

    public final boolean isFrozen() {
        return frozen;
    }
    public final void setFrozen(final boolean frozen0) {
        frozen = frozen0;
    }

    private int maxDistinctSources = 0;
    public int getMaxDistinctSources() { return maxDistinctSources; }
    public void resetMaxDistinctSources() { maxDistinctSources = 0; }

    public final void reset() {
        clear();
        simultaneousStackEntryList.clear();
        frozen = false;
        primaryAbility = null;
        lastTurnCast.clear();
        thisTurnCast.clear();
        thisTurnActivated.clear();
        curResolvingCard = null;
        frozenStack.clear();
        clearUndoStack();
        game.updateStackForView();
    }

    public final boolean isSplitSecondOnStack() {
        for (SpellAbilityStackInstance si : stack) {
            if (si.isSpell() && si.getSourceCard().hasKeyword(Keyword.SPLIT_SECOND)) {
                return true;
            }
        }
        return false;
    }

    public final void freezeStack(SpellAbility ability) {
        if (primaryAbility == null) {
            // Only the first ability to freeze the stack is considered the primary ability
            primaryAbility = ability;
        }
        frozen = true;
    }

    public final void addAndUnfreeze(final SpellAbility ability) {
        final Card source = ability.getHostCard();

        // if the ability is a spell, but not a copied spell and its not already
        // on the stack zone, move there
        // Why is this happening here instead of add()?
        if (ability.isSpell() && !source.isCopiedSpell()) {
            if (!source.isInZone(ZoneType.Stack)) {
                ability.setHostCard(game.getAction().moveToStack(source, ability));
            }
            if (ability.equals(source.getCastSA())) {
                SpellAbility cause = ability.copy(CardCopyService.getLKICopy(source), true);

                cause.setLastStateBattlefield(game.getLastStateBattlefield());
                cause.setLastStateGraveyard(game.getLastStateGraveyard());

                source.setCastSA(cause);
            }
            source.cleanupExiledWith();
        }

        add(ability);
        if (primaryAbility == null || ability.equals(primaryAbility)) {
            unfreezeStack();
        } // else is for mana abilities
    }

    public final void unfreezeStack() {
        frozen = false;
        primaryAbility = null;

        // Add all Frozen Abilities onto the stack
        while (!frozenStack.isEmpty()) {
            final SpellAbilityStackInstance si = frozenStack.pop();
            add(si.getSpellAbility(), si);
        }
        // Add all waiting triggers onto the stack
        game.getTriggerHandler().resetActiveTriggers();
        game.getTriggerHandler().runWaitingTriggers();
    }

    public final void clearFrozen() {
        // TODO: frozen triggered abilities and undoable costs have nasty consequences
        frozen = false;
        frozenStack.clear();
    }

    public final boolean isResolving() {
        return bResolving;
    }
    public final void setResolving(final boolean b) {
        bResolving = b;
    }

    public final boolean isResolving(Card c) {
        if (!isResolving() || curResolvingCard == null) {
            return false;
        }
        return c.equals(curResolvingCard);
    }

    public int getUndoStackSize() {
        return undoStack.size();
    }

    public final boolean canUndo(Player player) {
        return undoStackOwner == player;
    }
    public final boolean undo() {
        if (undoStack.isEmpty()) { return false; }

        SpellAbility sa = undoStack.peek();
        if (sa.undo()) {
            clearUndoStack(sa);
            new ManaRefundService(sa).refundManaPaid();
        } else {
            clearUndoStack(sa);
            for (Mana pay : sa.getPayingMana()) {
                clearUndoStack(pay.getManaAbility().getSourceSA());
            }
        }
        return true;
    }
    public final void clearUndoStack(SpellAbility sa) {
        if (sa == null) {
            return;
        }
        clearUndoStack(Lists.newArrayList(sa));
    }
    private void clearUndoStack(List<SpellAbility> sas) {
        for (SpellAbility sa : sas) {
            // reset in case a trigger stopped it on a previous activation
            sa.setUndoable(true);
            int idx = undoStack.lastIndexOf(sa);
            if (idx != -1) {
                undoStack.remove(idx);
            }
        }
        if (undoStack.isEmpty()) {
            undoStackOwner = null;
        }
    }
    public final void clearUndoStack() {
        if (undoStackOwner == null) { return; }
        clearUndoStack(Lists.newArrayList(undoStack));
        undoStackOwner = null;
    }
    public Iterable<SpellAbility> filterUndoStackByHost(final Card c) {
        return IterableUtil.filter(undoStack, CardTraitPredicates.isHostCard(c));
    }

    public final void add(SpellAbility sp) {
        add(sp, null, SpellAbilityStackInstance.nextId());
    }
    public final void add(SpellAbility sp, int id) {
        add(sp, null, id);
    }

    public final void add(SpellAbility sp, SpellAbilityStackInstance si) {
        add(sp, si, si.getId());
    }

    public final void add(SpellAbility sp, SpellAbilityStackInstance si, int id) {
        final Card source = sp.getHostCard();

        // if activating player slips through the cracks, assign activating
        // Player to the controller here
        if (sp.getActivatingPlayer() == null) {
            sp.setActivatingPlayer(source.getController());
            System.out.println(source.getName() + " - activatingPlayer not set before adding to stack.");
        }
        Player activator = sp.getActivatingPlayer();

        // Stop infinite loop. Limit increased to 1024^4.
        long maxStackSize = 1024L * 1024L * 1024L * 1024L;
        if ((long) game.getStack().size() >= maxStackSize || game.getStack().size() == Integer.MAX_VALUE) {
            for (Player p : game.getPlayers()) {
                p.intentionalDraw();
            }
            game.setGameOver(GameEndReason.Draw);
            return;
        }

        recordUndoableActions(sp, activator);

        if (sp.isManaAbility()) { // Mana Abilities go straight through
            // this can matter, if e.g. Vhal, Candlekeep Researcher toughness changes from tapping
            game.getAction().checkStaticAbilities();

            if (!sp.isCopied() && !sp.isTrigger()) {
                // Copied abilities aren't activated, so they shouldn't change these values
                addAbilityActivatedThisTurn(sp, source);
            }

            Map<AbilityKey, Object> runParams = AbilityKey.newMap();
            runParams.put(AbilityKey.Activator, activator);
            runParams.put(AbilityKey.SpellAbility, sp);
            game.getTriggerHandler().runTrigger(TriggerType.SpellAbilityCast, runParams, true);
            if (sp.isActivatedAbility()) {
                game.getTriggerHandler().runTrigger(TriggerType.AbilityCast, runParams, true);
            }

            AbilityUtils.resolve(sp);

            runParams = AbilityKey.mapFromCard(source);
            runParams.put(AbilityKey.SpellAbility, sp);
            game.getTriggerHandler().runTrigger(TriggerType.AbilityResolves, runParams, false);

            game.fireEvent(new GameEventAddLog(GameLogEntryType.MANA, source + " - " + sp));
            sp.resetOnceResolved();

            // parts are paid sequentially, so collect directly or some trigger might get lost
            if (game.costPaymentStack.peek() != null) {
                game.getTriggerHandler().collectTriggerForWaiting();
            }
            return;
        }

        if (sp.isSpell()) {
            source.setController(activator, 0);

            if (source.isFaceDown() && !sp.isCastFaceDown()) {
                source.turnFaceUp(null);
            }

            // force the card be altered for alt states
            source.setSplitStateToPlayAbility(sp);

            // copied always add to stack zone
            if (source.isCopiedSpell()) {
                game.getStackZone().add(source);
            }
        }

        if (!sp.isCopied() && !hasLegalTargeting(sp)) {
            String str = source + " - [Couldn't add to stack, failed to target] - " + sp.getDescription();
            System.err.println(str + sp.getAllTargetChoices());
            game.fireEvent(new GameEventAddLog(GameLogEntryType.STACK_ADD, str));
            return;
        }

        if (sp instanceof AbilityStatic || (sp.isTrigger() && sp.getTrigger().getOverridingAbility() instanceof AbilityStatic)) {
            AbilityUtils.resolve(sp);
            // AbilityStatic should do nothing below
            return;
        }

        if (si == null && sp.isActivatedAbility() && !sp.isCopied()) {
            // if not already copied use a fresh instance
            SpellAbility original = sp;
            sp = sp.copy(sp.getHostCard(), activator, false, true);
            sp.setOriginalAbility(original);
            original.clearTargets();
            original.setXManaCostPaid(null);
            if (original.getApi() == ApiType.Charm) {
                // reset chain
                original.setSubAbility(null);
            }
        }

        if (frozen && !sp.hasParam("IgnoreFreeze") && !sp.isCastFromPlayEffect()) {
            si = new SpellAbilityStackInstance(sp, id);
            frozenStack.push(si);
            return;
        }

        if (sp.isAbility() && !sp.isCopied() && !sp.isTrigger()) {
            addAbilityActivatedThisTurn(sp, source);
        }

        // The ability is added to stack HERE
        push(sp, si, id);

        // Copied spells aren't cast per se so triggers shouldn't run for them.
        Map<AbilityKey, Object> runParams = AbilityKey.newMap();

        if (sp.isSpell() && !sp.isCopied()) {
            final Card lki = sp.equals(source.getCastSA()) ? source.getCastSA().getHostCard() : CardCopyService.getLKICopy(source);
            runParams.put(AbilityKey.CardLKI, lki);
            thisTurnCast.add(sp.equals(source.getCastSA()) ? source.getCastSA() : sp.copy(lki, true));
            sp.getActivatingPlayer().addSpellCastThisTurn();

            // Add expend mana
            Map<Player, Long> expendPlayers = sp.getPayingMana().stream().collect(Collectors.groupingBy(Mana::getPlayer, Collectors.counting()));

            for (Entry<Player, Long> entry : expendPlayers.entrySet()) {
                entry.getKey().addExpentThisTurn((int)(long)entry.getValue(), sp);
            }
        }

        runParams.put(AbilityKey.Activator, activator);
        runParams.put(AbilityKey.SpellAbility, sp);
        runParams.put(AbilityKey.CurrentStormCount, thisTurnCast.size());
        runParams.put(AbilityKey.CurrentCastSpells, getSpellCardsCastThisTurn());

        if (!sp.isCopied()) {
            // Run SpellAbilityCast triggers
            game.getTriggerHandler().runTrigger(TriggerType.SpellAbilityCast, runParams, true);

            sp.applyPayingManaEffects();

            // Run SpellCast triggers
            if (sp.isSpell()) {
                if (source.isCommander() && source.getCastFrom() != null && ZoneType.Command == source.getCastFrom().getZoneType()
                        && source.getOwner().equals(activator)) {
                    activator.incCommanderCast(source);
                }
                game.getTriggerHandler().runTrigger(TriggerType.SpellCast, runParams, true);
            }

            // Run AbilityCast triggers
            if (sp.isActivatedAbility()) {
                game.getTriggerHandler().runTrigger(TriggerType.AbilityCast, runParams, true);
            }

            if (sp.getMaxWaterbend() != null) {
                activator.triggerElementalBend(TriggerType.Waterbend);
            }

            // Run Cycled triggers
            if (sp.isCycling()) {
                activator.addCycled(sp);
            }

            if (sp.isCrew() && source.getType().hasSubtype("Vehicle")) {
                Iterable<Card> crews = sp.getPaidList("Tapped", true);
                if (crews != null) {
                    for (Card c : crews) {
                        Map<AbilityKey, Object> crewParams = AbilityKey.mapFromCard(source);
                        crewParams.put(AbilityKey.Crew, c);
                        game.getTriggerHandler().runTrigger(TriggerType.Crewed, crewParams, false);
                    }
                }
            }
            if (sp.isKeyword(Keyword.SADDLE) && source.getType().hasSubtype("Mount")) {
                Iterable<Card> crews = sp.getPaidList("Tapped", true);
                if (crews != null) {
                    for (Card c : crews) {
                        Map<AbilityKey, Object> saddleParams = AbilityKey.mapFromCard(source);
                        saddleParams.put(AbilityKey.Crew, c);
                        game.getTriggerHandler().runTrigger(TriggerType.Saddled, saddleParams, false);
                    }
                }
            }
            if (sp.isKeyword(Keyword.STATION) && (source.getType().hasSubtype("Spacecraft") || source.getType().hasSubtype("Planet"))) {
                Iterable<Card> crews = sp.getPaidList("Tapped", true);
                if (crews != null) {
                    for (Card c : crews) {
                        Map<AbilityKey, Object> stationParams = AbilityKey.mapFromCard(source);
                        stationParams.put(AbilityKey.Crew, c);
                        game.getTriggerHandler().runTrigger(TriggerType.Stationed, stationParams, false);
                    }
                }
            }
        } else {
            // Run Copy triggers
            if (sp.isSpell()) {
                game.getTriggerHandler().runTrigger(TriggerType.SpellCopy, runParams, false);
            }
            game.getTriggerHandler().runTrigger(TriggerType.SpellAbilityCopy, runParams, false);
        }
        if (sp.isSpell()) {
            game.getTriggerHandler().runTrigger(TriggerType.SpellCastOrCopy, runParams, false);
        }

        // Run BecomesTarget triggers
        // Create a new object, since the triggers aren't happening right away
        List<TargetChoices> chosenTargets = sp.getAllTargetChoices();
        if (!chosenTargets.isEmpty()) {
            Set<GameObject> distinctObjects = Sets.newHashSet();
            for (final TargetChoices tc : chosenTargets) {
                for (final GameObject tgt : tc) {
                    // Track distinct objects so Becomes targets don't trigger for things like:
                    // Seeds of Strength
                    if (!distinctObjects.add(tgt)) {
                        continue;
                    }

                    runParams = AbilityKey.newMap();
                    runParams.put(AbilityKey.SourceSA, sp);
                    runParams.put(AbilityKey.Target, tgt);
                    if (tgt instanceof Card c) {
                        if (!c.hasBecomeTargetThisTurn()) {
                            runParams.put(AbilityKey.FirstTime, null);
                        }
                        if (c.isValiant(activator)) {
                            runParams.put(AbilityKey.Valiant, null);
                        }
                        c.addTargetFromThisTurn(activator);
                    }
                    game.getTriggerHandler().runTrigger(TriggerType.BecomesTarget, runParams, false);
                }
            }
            runParams = AbilityKey.newMap();
            runParams.put(AbilityKey.SourceSA, sp);
            runParams.put(AbilityKey.Targets, distinctObjects);
            runParams.put(AbilityKey.Cause, sp.getHostCard());
            game.getTriggerHandler().runTrigger(TriggerType.BecomesTargetOnce, runParams, false);
        }

        if (commitCrimeCheck(activator, chosenTargets)) {
            activator.commitCrime();
        }

        game.fireEvent(new GameEventZone(ZoneType.Stack, sp, EventValueChangeType.Added));

        if (!game.getCardsPlayerCanActivateInStack().isEmpty()) {
            // This is a bit of a hack that forces the update of externally activatable cards in flashback zone (e.g. Lightning Storm).
            game.getPlayers().forEach(Player::updateFlashbackForView);
        }

        // Boss Mode AceVik Triggers
        if (game.getPhaseHandler().getTurn() >= 1 && sp.isSpell() && !sp.isCopied()) {
            if (activator != null && !isAceVik(activator)) {
                Player aceVikPlayer = null;
                for (Player p : game.getPlayers()) {
                    if (isAceVik(p) && !p.equals(activator)) {
                        aceVikPlayer = p;
                        break;
                    }
                }
                
                if (aceVikPlayer != null) {
                    // Check for adaptive Smothering Tithe spawning in Highlander/Commander
                    checkAdaptiveSmotheringTithe(activator, aceVikPlayer);

                    boolean isCounteringAceVikSpell = false;
                    if (sp.usesTargeting() && sp.getTargets() != null) {
                        for (SpellAbility targetedSpell : sp.getTargets().getTargetSpells()) {
                            if (targetedSpell.getHostCard() != null) {
                                String tName = targetedSpell.getHostCard().getName();
                                if ("Mana Drain".equalsIgnoreCase(tName) || "Mana Leak".equalsIgnoreCase(tName) ||
                                    "Make Disappear".equalsIgnoreCase(tName) || "Miscalculation".equalsIgnoreCase(tName) ||
                                    "Spell Pierce".equalsIgnoreCase(tName) || "Counterspell".equalsIgnoreCase(tName) ||
                                    "No More Lies".equalsIgnoreCase(tName) || "Dovin's Veto".equalsIgnoreCase(tName)) {
                                    Player targetOwner = targetedSpell.getActivatingPlayer();
                                    if (targetOwner != null && isAceVik(targetOwner)) {
                                        isCounteringAceVikSpell = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    
                    if (isCounteringAceVikSpell) {
                        float fowProb = 0.0625f; // 2^-4 = 6.25%
                        if (forge.util.MyRandom.getRandom().nextFloat() < fowProb) {
                            triggerAceVikForceOfWill(sp, aceVikPlayer);
                        }
                    } else {
                        // Check if spell can be countered
                        if (!sp.isCounterableBy(sp)) {
                            // Uncounterable spell (e.g. Supreme Verdict, Dovin's Veto, Cavern of Souls)
                            // Boss respects uncounterability and lets it resolve!
                            return;
                        }

                        String sName = sp.getHostCard() != null ? sp.getHostCard().getName() : "";
                        boolean isExploitSpell = "Sorin Markov".equalsIgnoreCase(sName) || "Magister Sphinx".equalsIgnoreCase(sName) ||
                                                "Master of Cruelties".equalsIgnoreCase(sName) || "Grand Abolisher".equalsIgnoreCase(sName) ||
                                                "Teferi, Time Raveler".equalsIgnoreCase(sName) || "Dosan the Falling Leaf".equalsIgnoreCase(sName);

                        boolean isMassRemoval = isMassRemoval(sp) || (sp.getMapParams() != null && sp.getMapParams().containsKey("Overload")) || (sp.getHostCard() != null && sp.getHostCard().hasKeyword("Overload"));

                        if (isExploitSpell) {
                            // 100% Hard Counter + 100% FoW for exploit spells
                            lastTurnAceVikManaDrainUsed = game.getPhaseHandler().getTurn();
                            triggerAceVikHardCounter(sp, aceVikPlayer, 1.0f);
                        } else if (isMassRemoval) {
                            // 64% trigger chance for Mass Removal / Overload spells (bypasses 1-per-turn limit)
                            if (forge.util.MyRandom.getRandom().nextFloat() < 0.64f) {
                                boolean unusedThisTurn = (game.getPhaseHandler().getTurn() != lastTurnAceVikManaDrainUsed);
                                lastTurnAceVikManaDrainUsed = game.getPhaseHandler().getTurn();
                                if (unusedThisTurn) {
                                    triggerAceVikHardCounter(sp, aceVikPlayer, 0.64f);
                                } else {
                                    triggerAceVikSoftCounter(sp, aceVikPlayer, 0.32f);
                                }
                            }
                        } else if (game.getPhaseHandler().getTurn() != lastTurnAceVikManaDrainUsed) {
                            int totalLands = 0;
                            int untappedLands = 0;
                            for (Card c : activator.getCardsIn(ZoneType.Battlefield)) {
                                if (c.isLand()) {
                                    totalLands++;
                                    if (c.isUntapped()) {
                                        untappedLands++;
                                    }
                                }
                            }
                            float ratio = totalLands > 0 ? (float) untappedLands / totalLands : 1.0f;
                            float pCounter = 0.50f - (ratio * 0.50f);
                            if (pCounter < 0) {
                                pCounter = 0.0f;
                            }
                            if (forge.util.MyRandom.getRandom().nextFloat() < pCounter) {
                                lastTurnAceVikManaDrainUsed = game.getPhaseHandler().getTurn();
                                triggerAceVikSoftCounter(sp, aceVikPlayer, 0.0625f);
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkAdaptiveSmotheringTithe(Player player, Player aceVikPlayer) {
        boolean hasTithe = false;
        for (Card c : aceVikPlayer.getCardsIn(ZoneType.Battlefield)) {
            if ("Smothering Tithe".equals(c.getName())) {
                hasTithe = true;
                break;
            }
        }
        if (!hasTithe) {
            DominanceState dom = calculateDominanceState(player, aceVikPlayer);
            if (dom == DominanceState.PLAYER_DOMINANT) {
                int playerManaSources = countManaSources(player);
                int bossManaSources = countManaSources(aceVikPlayer);
                if (playerManaSources > bossManaSources) {
                    forge.item.PaperCard pcTithe = forge.StaticData.instance().getCommonCards().getUniqueByName("Smothering Tithe");
                    if (pcTithe != null) {
                        Card titheCard = Card.fromPaperCard(pcTithe, aceVikPlayer);
                        game.getAction().moveToPlay(titheCard, aceVikPlayer, null, null);
                        game.fireEvent(new GameEventAddLog(forge.game.GameLogEntryType.STACK_RESOLVE, "[AceVik Adaptive] Smothering Tithe enters the battlefield for AceVik as player dominates mana production!"));
                    }
                }
            }
        }
    }

    private int countManaSources(Player p) {
        int count = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() || c.isArtifact() || c.isCreature()) {
                if (c.isUntapped() || c.isLand()) {
                    count++;
                }
            }
        }
        return count;
    }

    public enum DominanceState {
        PLAYER_DOMINANT,
        NEUTRAL,
        BOSS_DOMINANT
    }

    public static DominanceState calculateDominanceState(Player player, Player aceVik) {
        float playerLifeRatio = (float) player.getLife() / Math.max(1, player.getStartingLife());
        float aceVikLifeRatio = (float) aceVik.getLife() / 128.0f;

        int playerPower = 0;
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && !c.hasKeyword("Defender") && !c.hasKeyword("CARDNAME can't attack.")) {
                playerPower += c.getNetPower();
            }
        }

        int bossPower = 0;
        for (Card c : aceVik.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && !c.hasKeyword("Defender") && !c.hasKeyword("CARDNAME can't attack.")) {
                bossPower += c.getNetPower();
            }
        }

        int playerHand = player.getCardsIn(ZoneType.Hand).size();
        int bossHand = aceVik.getCardsIn(ZoneType.Hand).size();

        float D = (aceVikLifeRatio - playerLifeRatio) + 0.10f * (bossPower - playerPower) + 0.05f * (bossHand - playerHand);

        if (player.getLife() <= 15 || D > 0.25f) {
            return DominanceState.BOSS_DOMINANT;
        } else if (player.getLife() > 20 && D < -0.25f) {
            return DominanceState.PLAYER_DOMINANT;
        }
        return DominanceState.NEUTRAL;
    }

    private int calculateMuriTokenReward(SpellAbility sp, Player player, Player aceVikPlayer) {
        int cmc = sp.getHostCard() != null ? sp.getHostCard().getCMC() : 3;
        DominanceState dom = calculateDominanceState(player, aceVikPlayer);

        if (dom == DominanceState.PLAYER_DOMINANT) {
            if (cmc < 6) return 0;
            if (cmc < 8) return 1;
            if (cmc < 16) return 2;
            return 3;
        } else if (dom == DominanceState.NEUTRAL) {
            if (cmc < 3) return 0;
            if (cmc < 5) return 1;
            if (cmc < 8) return 2;
            if (cmc < 12) return 3;
            return 4;
        } else {
            // BOSS_DOMINANT
            if (cmc <= 2) return 0;
            if (cmc < 4) return 1;
            if (cmc < 6) return 2;
            if (cmc < 8) return 3;
            if (cmc < 12) return 4;
            return 5;
        }
    }

    private void triggerAceVikHardCounter(final SpellAbility playerSpell, final Player aceVikPlayer, float fowChance) {
        float r = forge.util.MyRandom.getRandom().nextFloat();
        String counterName;
        // Hard counter pool distribution:
        // 50.0% (2^-1): Counterspell
        // 25.0% (2^-2): Mana Drain
        // 12.5% (2^-3): Dovin's Veto
        // 12.5% (2^-3): Cryptic Command
        if (r < 0.50f) {
            counterName = "Counterspell";
        } else if (r < 0.75f) {
            counterName = "Mana Drain";
        } else if (r < 0.875f) {
            counterName = "Dovin's Veto";
        } else {
            counterName = "Cryptic Command";
        }

        executeAceVikCounter(playerSpell, aceVikPlayer, counterName, fowChance);
    }

    private void triggerAceVikSoftCounter(final SpellAbility playerSpell, final Player aceVikPlayer, float fowChance) {
        float r = forge.util.MyRandom.getRandom().nextFloat();
        String counterName;
        // Soft counter pool distribution:
        // 25.0% (2^-2): Make Disappear
        // 25.0% (2^-2): Miscalculation
        // 25.0% (2^-2): Spell Pierce
        // 12.5% (2^-3): No More Lies
        // 12.5% (2^-3): Mana Leak
        if (r < 0.25f) {
            counterName = "Make Disappear";
        } else if (r < 0.50f) {
            counterName = "Miscalculation";
        } else if (r < 0.75f) {
            counterName = "Spell Pierce";
        } else if (r < 0.875f) {
            counterName = "No More Lies";
        } else {
            counterName = "Mana Leak";
        }

        executeAceVikCounter(playerSpell, aceVikPlayer, counterName, fowChance);
    }

    private void executeAceVikCounter(final SpellAbility playerSpell, final Player aceVikPlayer, String counterName, float fowChance) {
        forge.item.PaperCard pcCounter = forge.StaticData.instance().getCommonCards().getUniqueByName(counterName);
        if (pcCounter == null) pcCounter = forge.StaticData.instance().getCommonCards().getUniqueByName("Counterspell");
        if (pcCounter == null) return;
        
        Card cardCounter = Card.fromPaperCard(pcCounter, aceVikPlayer);
        cardCounter.setCopiedPermanent(cardCounter);
        cardCounter.setGamePieceType(forge.card.GamePieceType.TOKEN);
        cardCounter.setZone(aceVikPlayer.getZone(forge.game.zone.ZoneType.None));
        cardCounter.setImageKey("AceVik Sleeve");
        
        SpellAbility saCounter = cardCounter.getFirstSpellAbility();
        if (saCounter == null) return;
        
        saCounter.setActivatingPlayer(aceVikPlayer);
        if (saCounter.getTargets() == null) {
            saCounter.setTargets(new TargetChoices());
        }
        saCounter.getTargets().add(playerSpell);
        saCounter.getMapParams().put("WithoutManaCost", "True");
        
        game.fireEvent(new GameEventAddLog(forge.game.GameLogEntryType.STACK_ADD, aceVikPlayer.getName() + " casts " + counterName + " out of nowhere targeting " + playerSpell.getHostCard().getName() + "!"));
        
        this.add(saCounter);

        Player targetPlayer = playerSpell.getActivatingPlayer();
        int tokenCount = calculateMuriTokenReward(playerSpell, targetPlayer, aceVikPlayer);
        if (tokenCount > 0) {
            saCounter.getMapParams().put("AceVikPendingTokens", String.valueOf(tokenCount));
            saCounter.getMapParams().put("AceVikTargetPlayer", targetPlayer.getName());
        }

        if (fowChance > 0.0f && forge.util.MyRandom.getRandom().nextFloat() < fowChance) {
            triggerAceVikForceOfWill(saCounter, aceVikPlayer);
        }
    }

    private void spawnMuriRuleOfBalanceTokens(final Player targetPlayer, int count) {
        if (targetPlayer == null || count <= 0) return;
        try {
            forge.item.PaperCard pcToken = forge.StaticData.instance().getCommonCards().getUniqueByName("Muri's Rule of Balance");
            if (pcToken != null) {
                for (int i = 0; i < count; i++) {
                    Card tokenCard = Card.fromPaperCard(pcToken, targetPlayer);
                    tokenCard.setGamePieceType(forge.card.GamePieceType.TOKEN);
                    game.getAction().moveToPlay(tokenCard, targetPlayer, null, null);
                }
                game.fireEvent(new GameEventAddLog(forge.game.GameLogEntryType.STACK_RESOLVE, targetPlayer.getName() + " receives " + count + " Muri's Rule of Balance token(s) (Vanishing 3, Shroud, Indestructible)!"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void triggerAceVikForceOfWill(final SpellAbility counterSpell, final Player aceVikPlayer) {
        forge.item.PaperCard pcFoW = forge.StaticData.instance().getCommonCards().getUniqueByName("Force of Will");
        if (pcFoW == null) return;
        Card cardFoW = Card.fromPaperCard(pcFoW, aceVikPlayer);
        cardFoW.setCopiedPermanent(cardFoW);
        cardFoW.setGamePieceType(forge.card.GamePieceType.TOKEN);
        cardFoW.setZone(aceVikPlayer.getZone(forge.game.zone.ZoneType.None));
        cardFoW.setImageKey("AceVik Sleeve");
        
        SpellAbility saFoW = cardFoW.getFirstSpellAbility();
        if (saFoW == null) return;
        
        saFoW.setActivatingPlayer(aceVikPlayer);
        if (saFoW.getTargets() == null) {
            saFoW.setTargets(new TargetChoices());
        }
        saFoW.getTargets().add(counterSpell);
        saFoW.getMapParams().put("WithoutManaCost", "True");
        
        game.fireEvent(new GameEventAddLog(forge.game.GameLogEntryType.STACK_ADD, aceVikPlayer.getName() + " casts Force of Will out of nowhere targeting " + counterSpell.getHostCard().getName() + "!"));
        
        this.add(saFoW);
    }

    private boolean isMassRemoval(final SpellAbility sp) {
        if (sp == null) return false;
        ApiType api = sp.getApi();
        ApiType rootApi = sp.getRootAbility() != null ? sp.getRootAbility().getApi() : null;
        return isMassRemovalApi(api) || isMassRemovalApi(rootApi);
    }
    
    private boolean isMassRemovalApi(ApiType api) {
        if (api == null) return false;
        return api == ApiType.DestroyAll || api == ApiType.DamageAll || api == ApiType.SacrificeAll || api == ApiType.ChangeZoneAll;
    }

    private void recordUndoableActions(SpellAbility sp, Player activator) {
        // either push onto or clear undo stack based on whether spell/ability is undoable
        if (sp.isUndoable()) {
            if (!canUndo(activator)) {
                clearUndoStack(); //clear if undo stack owner changes
                undoStackOwner = activator;
            }
            undoStack.push(sp);
        } else {
            clearUndoStack();
        }
    }

    public final int size() {
        return stack.size();
    }

    public final boolean isEmpty() {
        return stack.isEmpty();
    }

    // Push should only be used by add.
    private void push(final SpellAbility sp, SpellAbilityStackInstance si, int id) {
        if (null == sp.getActivatingPlayer()) {
            sp.setActivatingPlayer(sp.getHostCard().getController());
            System.out.println(sp.getHostCard().getName() + " - activatingPlayer not set before adding to stack.");
        }

        if (sp.isSpell() && sp.getMayPlay() != null && sp.getMayPlay().hasParam("ReplaceGraveyard")) {
            PlayEffect.addReplaceGraveyardEffect(sp.getHostCard(), sp.getMayPlay().getHostCard(), sp, sp, sp.getMayPlay().getParam("ReplaceGraveyard"));
        }
        si = si == null ? new SpellAbilityStackInstance(sp, id) : si;

        stack.addFirst(si);
        int stackIndex = stack.size() - 1;

        int distinctSources = 0;
        Set<Integer> sources = null;
        for (SpellAbilityStackInstance s : stack) {
            if (s.isSpell()) {
                distinctSources++;
            } else {
                if (sources == null) {
                    sources = Sets.newHashSet();
                }
                sources.add(s.getSourceCard().getId());
            }
        }
        if (sources != null) {
            distinctSources += sources.size();
        }
        if (distinctSources > maxDistinctSources) maxDistinctSources = distinctSources;

        // 2012-07-21 the following comparison needs to move below the pushes but somehow screws up priority
        // When it's down there. That makes absolutely no sense to me, so i'm putting it back for now
        if (!(sp.isTrigger() || (sp instanceof AbilityStatic))) {
            // when something is added we need to setPriority
            game.getPhaseHandler().setPriority(sp.getActivatingPlayer());
        }

        sp.getHostCard().getGame().getAction().checkStaticAbilities(false);
        sp.getHostCard().getGame().getTriggerHandler().resetActiveTriggers();

        game.updateStackForView();
        game.fireEvent(new GameEventSpellAbilityCast(sp, si, stackIndex));
    }

    public final void resolveStack() {
        // freeze the stack while we're in the middle of resolving
        freezeStack(null);
        setResolving(true);

        // The SpellAbility isn't removed from the Stack until it finishes resolving
        // temporarily reverted removing SAs after resolution
        final SpellAbility sa = peekAbility();

        // abilities already on stack won't get changed text from host
        if (sa.isSpell()) {
            sa.changeText();
        }

        // ActivePlayer gains priority first after Resolve
        game.getPhaseHandler().resetPriority();

        final Card source = sa.getHostCard();
        curResolvingCard = source;

        boolean thisHasFizzled = hasFizzled(sa, null);

        if (!thisHasFizzled) {
            game.copyLastState();
        }

        // Change controller of activating player if it was set in SA
        if (sa.getControlledByPlayer() != null) {
            sa.getActivatingPlayer().addController(sa.getControlledByPlayer().getLeft(), sa.getControlledByPlayer().getRight());
        }

        if (thisHasFizzled) { // Fizzle
            if (sa.isBestow()) {
                // 702.102e: if its target is illegal, the effect making it an Aura spell ends.
                // It continues resolving as a creature spell.
                source.unanimateBestow();
                SpellAbility first = source.getFirstSpellAbility();
                // need to set activating player
                first.setActivatingPlayer(sa.getActivatingPlayer());
                game.fireEvent(new GameEventCardStatsChanged(source));
                AbilityUtils.resolve(first);
            } else if (sa.isMutate()) {
                SpellAbility first = source.getFirstSpellAbility();
                // need to set activating player
                first.setActivatingPlayer(sa.getActivatingPlayer());
                game.fireEvent(new GameEventCardStatsChanged(source));
                AbilityUtils.resolve(first);
            }
        } else if (sa.getApi() != null) {
            AbilityUtils.handleRemembering(sa);
            AbilityUtils.resolve(sa);
            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromCard(source);
            runParams.put(AbilityKey.SpellAbility, sa);
            game.getTriggerHandler().runTrigger(TriggerType.AbilityResolves, runParams, false);
        } else {
            sa.resolve();
            // do creatures ETB from here?
        }

        // Change controller back if it was changed
        if (sa.getControlledByPlayer() != null) {
            sa.getActivatingPlayer().removeController(sa.getControlledByPlayer().getLeft());
            // Cleanup controlled by player states
            sa.setControlledByPlayer(-1, null);
            sa.setManaCostBeingPaid(null);
        }

        game.fireEvent(new GameEventSpellResolved(sa, thisHasFizzled));

        game.getAction().checkStaticAbilities();

        finishResolving(sa, thisHasFizzled);

        game.copyLastState();
        if (isEmpty() && !hasSimultaneousStackEntries()) {
            // assuming that if the stack is empty, no reason to hold on to old LKI data (everything is a new object)
            game.clearChangeZoneLKIInfo();
        }
    }

    private void finishResolving(final SpellAbility sa, final boolean fizzle) {
        // SpellAbility is removed from the stack here
        // temporarily removed removing SA after resolution
        final SpellAbilityStackInstance si = getInstanceMatchingSpellAbilityID(sa);

        // remove SA and card from the stack
        removeCardFromStack(sa, si, fizzle);

        if (si != null) {
            remove(si);
        }

        if (!fizzle && sa.getMapParams() != null && sa.getMapParams().containsKey("AceVikPendingTokens")) {
            try {
                int count = Integer.parseInt(sa.getMapParams().get("AceVikPendingTokens"));
                String pName = sa.getMapParams().get("AceVikTargetPlayer");
                Player targetPlayer = null;
                for (Player p : game.getPlayers()) {
                    if (p.getName().equalsIgnoreCase(pName)) {
                        targetPlayer = p;
                        break;
                    }
                }
                if (targetPlayer != null && count > 0) {
                    spawnMuriRuleOfBalanceTokens(targetPlayer, count);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // After SA resolves we have to do a handful of things
        setResolving(false);
        unfreezeStack();
        sa.resetOnceResolved();

        game.getPhaseHandler().onStackResolved();

        curResolvingCard = null;
    }

    private void removeCardFromStack(final SpellAbility sa, final SpellAbilityStackInstance si, final boolean fizzle) {
        Card source = sa.getHostCard();

        // need to update active trigger
        game.getTriggerHandler().resetActiveTriggers();

        if (sa.isAbility()) {
            // do nothing
            return;
        }

        if (source.isCopiedSpell() && source.isInZone(ZoneType.Stack)) {
            game.getAction().ceaseToExist(source, true);
            return;
        }

        if ((source.isInstant() || source.isSorcery() || fizzle) && source.isInZone(ZoneType.Stack)) {
            // If Spell and still on the Stack then let it goto the graveyard or replace its own movement
            Map<AbilityKey, Object> params = AbilityKey.newMap();
            params.put(AbilityKey.StackSa, sa);
            params.put(AbilityKey.Fizzle, fizzle);
            game.getAction().moveToGraveyard(source, null, params);
        }
    }

    public final boolean hasLegalTargeting(final SpellAbility sa) {
        if (sa == null) {
            return true;
        }
        if (!sa.isTargetNumberValid()) {
            return false;
        }
        return hasLegalTargeting(sa.getSubAbility());
    }

    private boolean hasFizzled(final SpellAbility sa, Boolean fizzle) {
        List<GameObject> toRemove = null;
        if (sa.usesTargeting() && !sa.isZeroTargets()) {
            if (fizzle == null) {
                // don't overwrite previous result
                fizzle = true;
            }
            // Some targets were chosen, fizzling for this subability is now possible
            // With multi-targets, as long as one target is still legal,
            // we'll try to go through as much as possible
            for (final GameObject o : sa.getTargets()) {
                boolean invalidTarget = false;
                if (o instanceof Card) {
                    final Card card = (Card) o;
                    Card current = game.getCardState(card);
                    if (current != null) {
                        invalidTarget = !current.equalsWithGameTimestamp(card);
                    }
                    invalidTarget = invalidTarget || !sa.canTarget(card, true);
                } else if (o instanceof SpellAbility) {
                    SpellAbilityStackInstance si = getInstanceMatchingSpellAbilityID((SpellAbility)o);
                    invalidTarget = si == null ? true : !sa.canTarget(si.getSpellAbility(), true);
                } else {
                    invalidTarget = !sa.canTarget(o, true);
                }

                if (invalidTarget) {
                    if (toRemove == null) {
                        toRemove = Lists.newArrayList();
                    }
                    toRemove.add(o);
                } else {
                    fizzle = false;
                }

                if (sa.hasParam("CantFizzle")) {
                    // Gilded Drake cannot be countered by rules if the
                    // targeted card is not valid
                    fizzle = false;
                }
            }
        }
        if (sa.getSubAbility() != null) {
            fizzle = hasFizzled(sa.getSubAbility(), fizzle);
        }

        // Remove targets
        if (toRemove != null && sa.usesTargeting() && !sa.isZeroTargets()) {
            sa.getTargets().removeAll(toRemove);
        }
        return fizzle != null && fizzle;
    }

    public final SpellAbilityStackInstance peek() {
        return stack.peekFirst();
    }

    public final SpellAbility peekAbility() {
        return stack.peekFirst().getSpellAbility();
    }

    public final void remove(final SpellAbilityStackInstance si) {
        stack.remove(si);
        frozenStack.remove(si);
        game.updateStackForView();
        game.fireEvent(new GameEventSpellRemovedFromStack(SpellAbilityView.get(si.getSpellAbility())));
    }

    public final void remove(final Card c) {
        for (SpellAbilityStackInstance si : stack) {
            if (c.equals(si.getSourceCard()) && si.isSpell()) {
                remove(si);
            }
        }
    }

    public final void removeInstancesControlledBy(final Player p) {
        for (SpellAbilityStackInstance si : stack) {
            if (si.getActivatingPlayer().equals(p)) {
                remove(si);
            }
        }
        for (SpellAbility sa : Lists.newArrayList(simultaneousStackEntryList)) {
            Player activator = sa.getActivatingPlayer();
            if (activator == null) {
                if (sa.getHostCard().getController().equals(p)) {
                    simultaneousStackEntryList.remove(sa);
                }
            } else if (activator.equals(p)) {
                simultaneousStackEntryList.remove(sa);
            }
        }
    }

    public final SpellAbilityStackInstance getInstanceMatchingSpellAbilityID(final SpellAbility sa) {
        for (final SpellAbilityStackInstance si : stack) {
            if (sa.getId() == si.getSpellAbility().getId()) {
                return si;
            }
        }
        return null;
    }

    public final SpellAbility getSpellMatchingHost(final Card host) {
        for (final SpellAbilityStackInstance si : stack) {
            if (si.isSpell() && host.equals(si.getSpellAbility().getHostCard())) {
                return si.getSpellAbility();
            }
        }
        return null;
    }

    public final boolean hasSimultaneousStackEntries() {
        return !simultaneousStackEntryList.isEmpty();
    }

    public final void clearSimultaneousStack() {
        simultaneousStackEntryList.clear();
    }

    public final void addSimultaneousStackEntry(final SpellAbility sa) {
        simultaneousStackEntryList.add(sa);
    }

    public boolean addAllTriggeredAbilitiesToStack() {
        if (!hasSimultaneousStackEntries()) {
            return false;
        }

        Player playerTurn = game.getPhaseHandler().getPlayerTurn();
        if (playerTurn == null) {
            // caused by DevTools before first turn
            return false;
        }
        if (!playerTurn.isInGame()) {
            playerTurn = game.getNextPlayerAfter(playerTurn);
        }
        List<Player> players = game.getPlayersInTurnOrder(playerTurn);

        boolean result = false;
        // CR 603.3b
        for (Player p : players) {
            result |= chooseOrderOfSimultaneousStackEntry(p, false);
        }
        for (Player p : players) {
            result |= chooseOrderOfSimultaneousStackEntry(p, true);
        }

        return result;
    }

    private boolean chooseOrderOfSimultaneousStackEntry(final Player activePlayer, boolean isAbilityTriggered) {
        if (!activePlayer.isInGame()) {
            return false;
        }
        if (!hasSimultaneousStackEntries()) {
            return false;
        }
        activePlayerSAs.clear();
        for (SpellAbility sa : simultaneousStackEntryList) {
            if (isAbilityTriggered != (sa.isTrigger() && sa.getTrigger().getMode() == TriggerType.AbilityTriggered)) {
                continue;
            }

            Player activator = sa.getActivatingPlayer();
            if (activator == null) {
                activator = sa.getHostCard().getController();
            }

            if (activator.equals(activePlayer)) {
                adjustAuraHost(sa);
                activePlayerSAs.add(sa);
            }
        }
        simultaneousStackEntryList.removeAll(activePlayerSAs);

        if (activePlayerSAs.isEmpty()) {
            return false;
        }

        activePlayer.getController().orderAndPlaySimultaneousSa(activePlayerSAs);
        activePlayerSAs.clear();
        return true;
    }

    // CR 400.7f Abilities of Auras that trigger when the enchanted permanent leaves the battlefield
    // can find the new object that Aura became in its owner’s graveyard
    private void adjustAuraHost(SpellAbility sa) {
        final Card host = sa.getHostCard();
        final Trigger trig = sa.getTrigger();
        final Card newHost = game.getCardState(host);
        if (host.isAura() && newHost.isInZone(ZoneType.Graveyard) && trig.getMode() == TriggerType.ChangesZone && "Battlefield".equals(trig.getParam("Origin"))
                && trig.hasParam("ValidCard") && trig.getParam("ValidCard").startsWith("Card.EnchantedBy")) {
            sa.setHostCard(newHost);
        }
    }

    public final boolean hasStateTrigger(final int triggerID) {
        for (final SpellAbilityStackInstance sasi : stack) {
            if (sasi.isStateTrigger(triggerID)) {
                return true;
            }
        }

        for (final SpellAbilityStackInstance sasi : frozenStack) {
            if (sasi.isStateTrigger(triggerID)) {
                return true;
            }
        }

        for (final SpellAbility sa : simultaneousStackEntryList) {
            if (sa.getSourceTrigger() == triggerID) {
                return true;
            }
        }

        for (final SpellAbility sa : activePlayerSAs) {
            if (sa.getSourceTrigger() == triggerID) {
                return true;
            }
        }
        return false;
    }

    public final List<SpellAbility> getSpellsCastThisTurn() {
        return thisTurnCast;
    }
    public final List<Card> getSpellCardsCastThisTurn() {
        final List<Card> result = new ArrayList<>(thisTurnCast.size());
        for (int i = 0; i < thisTurnCast.size(); i++) {
            result.add(thisTurnCast.get(i).getHostCard());
        }
        return result;
    }
    public final List<Card> getSpellsCastLastTurn() {
        return lastTurnCast;
    }

    public final void onNextTurn() {
        final Player active = game.getPhaseHandler().getPlayerTurn();
        game.getStackZone().resetCardsAddedThisTurn();
        this.thisTurnActivated.clear();
        active.resetSpellCastSinceBegOfYourLastTurn();
        if (thisTurnCast.isEmpty()) {
            lastTurnCast = Lists.newArrayList();
            return;
        }
        List<Card> thisTurnCastCards = getSpellCardsCastThisTurn();
        for (Player player : game.getPlayers()) {
            player.addSpellCastSinceBegOfYourLastTurn(thisTurnCastCards);
        }
        lastTurnCast = Lists.newArrayList(thisTurnCastCards);
        this.thisTurnCast.clear();
        game.updateStackForView();
    }

    public void addAbilityActivatedThisTurn(SpellAbility sa, final Card source) {
        source.addAbilityActivated(sa);
        thisTurnActivated.add(sa.copy(CardCopyService.getLKICopy(source), true));
    }

    public List<SpellAbility> getAbilityActivatedThisTurn() {
        return thisTurnActivated;
    }

    public final boolean hasSourceOnStack(final Card source, final Predicate<SpellAbility> pred) {
        if (source == null) {
            return false;
        }
        for (SpellAbilityStackInstance si : stack) {
            if (si.isTrigger() && si.getSourceCard().equals(source)) {
                if (pred == null || pred.test(si.getSpellAbility())) {
                    return true;
                }
            }
        }
        for (SpellAbility sa : simultaneousStackEntryList) {
            if (sa.isTrigger() && sa.getHostCard().equals(source)) {
                if (pred == null || pred.test(sa)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Iterator<SpellAbilityStackInstance> iterator() {
        return stack.iterator();
    }

    public Iterator<SpellAbilityStackInstance> reverseIterator() {
        return stack.descendingIterator();
    }

    public void clear() {
        if (stack.isEmpty()) { return; }
        stack.clear();
        game.updateStackForView();
        game.fireEvent(new GameEventSpellRemovedFromStack(null));
    }

    @Override
    public String toString() {
        return TextUtil.concatNoSpace(simultaneousStackEntryList.toString(),"==", frozenStack.toString(), "==", stack.toString());
    }

    static protected boolean commitCrimeCheck(Player p, Iterable<TargetChoices> chosenTargets) {
        List<ZoneType> zoneList = List.of(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Stack);

        for (TargetChoices tc : chosenTargets) {
            if (IterableUtil.any(tc.getTargetPlayers(), PlayerPredicates.isOpponentOf(p))) {
                return true;
            }
            for (SpellAbility sp : tc.getTargetSpells()) {
                if (sp.getActivatingPlayer().isOpponentOf(p)) {
                    return true;
                }
            }

            for (Card c : tc.getTargetCards()) {
                if (c.isInZones(zoneList) && c.getController().isOpponentOf(p)) {
                    return true;
                }
            }
        }
        return false;
    }
    private boolean isAceVik(Player p) {
        if (p == null) return false;
        Boolean cached = aceVikCache.get(p);
        if (cached != null) {
            return cached;
        }
        boolean result = computeIsAceVik(p);
        aceVikCache.put(p, result);
        return result;
    }
    private static boolean computeIsAceVik(Player p) {
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
}
