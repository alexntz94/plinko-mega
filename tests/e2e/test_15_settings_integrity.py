"""Settings edited in the database (bet limits): the game stops within a minute and the panel raises the alarm; the
settings put back exactly restore it (review 3.1)."""
from conftest import app, sql, wait_session


def test_edited_bet_limits_stop_the_game(check, admin, tester):
    old_max = sql("SELECT value FROM setting WHERE name='game.maxBet'")[1]
    if old_max:
        app("UPDATE setting SET value='1000' WHERE name='game.maxBet'")
    else:
        app("INSERT INTO setting (name, value, updated_at) VALUES ('game.maxBet', '1000', NOW(3))")
    # the game keeps using the last trusted settings from memory, and finds the edit within a minute (review 3.1)
    status, body = wait_session(tester.pid, 503)
    check("bet limits edited in the database stop the game (within a minute)", status == 503, (status, body))
    _, _, html = admin.get("/admin")
    check("admin panel raises an alarm for edited settings", "Bet limits / game switch were changed outside" in html)
    if old_max:
        app(f"UPDATE setting SET value='{old_max}' WHERE name='game.maxBet'")
    else:
        sql("DELETE FROM setting WHERE name='game.maxBet'")
    status, _ = wait_session(tester.pid, 200)
    check("settings put back exactly restore the game", status == 200, status)
