package forge.game.spellability;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.zone.ZoneType;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

/**
 * Tests for the Rebound keyword and delayed trigger execution.
 */
public class ReboundTest extends AITest {

    @Test
    public void reboundKeywordAddsExileReplacementEffect() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);

        Card ephemerate = addCardToZone("Ephemerate", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);

        boolean hasReboundReplacement = false;
        for (ReplacementEffect re : ephemerate.getReplacementEffects()) {
            if (re.toString().toLowerCase().contains("rebound")) {
                hasReboundReplacement = true;
                break;
            }
        }
        assertTrue("Ephemerate should have a Rebound replacement effect", hasReboundReplacement);
    }

    @Test
    public void reboundSpellExilesOnResolutionWhenCastFromHand() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);

        addCards("Plains", 2, p);
        Card targetCreature = addCard("Grizzly Bears", p);
        Card ephemerate = addCardToZone("Ephemerate", p, ZoneType.Hand);

        SpellAbility sa = ephemerate.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        sa.getTargets().add(targetCreature);

        boolean played = forge.ai.ComputerUtil.playStack(sa, p, game);
        assertTrue("Spell should be successfully played to stack", played);

        // Resolve spell via stack
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);

        // Spell should be in exile, not graveyard
        assertTrue("Rebound spell should be in exile after resolution",
                p.getZone(ZoneType.Exile).contains(ephemerate));
        assertFalse("Rebound spell should not be in graveyard",
                p.getZone(ZoneType.Graveyard).contains(ephemerate));
    }
}
