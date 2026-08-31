package forge.gamesimulationtests;

import com.google.common.collect.Lists;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardTraitChanges;
import forge.game.card.perpetual.PerpetualAbilities;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Tests for ReplacementHandler, particularly around perpetual replacement effects.
 */
public class ReplacementHandlerTest extends SimulationTest {

    /**
     * Tests that a card with a perpetual "enters tapped" replacement effect
     * can enter the battlefield without causing a StackOverflowError.
     * <p>
     * This test verifies the fix for the infinite recursion issue where:
     * <ol>
     *   <li>A perpetual replacement effect returns ReplacementResult.Updated</li>
     *   <li>The recursive call creates a new LKI copy via CardCopyService.getLKICopy</li>
     *   <li>The LKI copy re-applied perpetual effects, creating duplicate ReplacementEffects</li>
     *   <li>The duplicate effects weren't in hasRun set, causing infinite recursion</li>
     * </ol>
     * <p>
     * The fix in CardCopyService.getLKICopy() passes applyEffects=false to setPerpetual(),
     * since perpetual effects are already copied via copyFrom(). This prevents duplicate
     * ReplacementEffect objects from being created.
     */
    @Test(timeOut = 2000) // 2 second timeout to catch infinite loops
    public void testPerpetualEntersTappedReplacementEffect() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        // Create a simple creature card
        Card creature = createCard("Runeclaw Bear", p1);
        creature.setGameTimestamp(game.getNextTimestamp());

        // Add card to hand first
        p1.getZone(ZoneType.Hand).add(creature);

        // Create a perpetual "enters tapped" replacement effect
        // This mimics what Boareskyr Tollkeeper does to its target
        long timestamp = game.getNextTimestamp();

        // First, add the SVar for the tap ability to the creature
        String tapAbility = "DB$ Tap | ETB$ True | Defined$ ReplacedCard";
        creature.setSVar("ETBTapped", tapAbility);

        // Parse and create the replacement effect
        String replaceStr = "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield | " +
                           "ReplaceWith$ ETBTapped | ReplacementResult$ Updated | " +
                           "Description$ This permanent enters tapped.";
        ReplacementEffect re = ReplacementHandler.parseReplacement(replaceStr, creature, true);

        // Add the replacement effect as a perpetual change to the card
        CardTraitChanges changes = new CardTraitChanges(
            null, null,
            Lists.newArrayList(re),
            null, null
        );
        creature.addPerpetual(new PerpetualAbilities(timestamp, changes));
        creature.addChangedCardTraits(null, null,
            Lists.newArrayList(re), null, null, timestamp, 0);

        // Now move the card from hand to battlefield
        // This should NOT cause a StackOverflowError
        game.getAction().moveTo(ZoneType.Battlefield, creature, null, null);

        // Verify the card entered the battlefield and is tapped
        assertTrue(creature.isInZone(ZoneType.Battlefield),
            "Card should be on the battlefield");
        assertTrue(creature.isTapped(),
            "Card should be tapped due to perpetual replacement effect");
    }

    /**
     * Tests that a replacement effect modifying a zone change (e.g. dying to graveyard replaced by exile)
     * correctly preserves the permanent's gameTimestamp on the copied card so ChangeZoneEffect
     * recognizes the card on the battlefield, removes it, and moves it to the replaced destination.
     */
    @Test(timeOut = 2000)
    public void testMovedReplacementEffectPreservesTimestampAndClearsBattlefield() {
        Game game = initAndCreateGame();
        Player p1 = game.getPlayers().get(0);
        Player p2 = game.getPlayers().get(1);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p1);

        // Put Kalitas, Traitor of Ghet on battlefield for p1
        Card kalitas = createCard("Kalitas, Traitor of Ghet", p1);
        game.getAction().moveTo(ZoneType.Battlefield, kalitas, null, null);

        // Put a creature on battlefield for p2
        Card bear = createCard("Grizzly Bears", p2);
        game.getAction().moveTo(ZoneType.Battlefield, bear, null, null);

        game.getAction().checkStateEffects(true);

        assertTrue(bear.isInZone(ZoneType.Battlefield), "Bear should be on battlefield");

        // Destroy the bear -> Should be replaced by exile, Kalitas triggers token creation
        game.getAction().destroy(bear, null, false, null);
        game.getAction().checkStateEffects(true);

        assertFalse(bear.isInZone(ZoneType.Battlefield), "Bear should no longer be on battlefield");
        assertFalse(p2.getZone(ZoneType.Graveyard).contains(bear), "Bear should not be in graveyard");
        assertTrue(p2.getZone(ZoneType.Exile).contains(bear), "Bear should be in exile");
    }
}
