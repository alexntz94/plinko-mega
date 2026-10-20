"""Bonuses: golden ball, hot slot and bonus meter. Four-eyes, limits on bonus size, each table's total RTP kept, decided
and signed by the server, frozen in the database; bonus settings edited in the database stop the game and can be
restored only as recorded; the meter carries over, is one signed chain, and a forged meter round pays nothing."""
import threading, time
from decimal import Decimal

from conftest import FAIR, api, app, bonus_form, fresh_token, mock_balance, sql, wait_session

RTP_QUERY = "SELECT ROUND(SUM(multiplier*probability), 6) FROM odds GROUP BY rows_count, risk ORDER BY rows_count, risk"


def count(query):
    return int(sql(query)[1] or 0)


def test_bonus(check, admin, second_admin, tester):
    pid = tester.pid
    _, _, html = admin.get("/admin/bonus")
    check("bonus page lists the golden ball, switched off", "Golden ball" in html and ">Off<" in html)
    bonus_requests = count("SELECT COUNT(*) FROM change_request WHERE type='BONUS'")
    admin.post_form("/admin/bonus/propose", bonus_form(True, "10", [("50", "100")]), "/admin/bonus")
    check("a bonus adding more than 15% on top of the base game cannot be proposed",
          count("SELECT COUNT(*) FROM change_request WHERE type='BONUS'") == bonus_requests)
    admin.post_form("/admin/bonus/propose", bonus_form(True, "1", [("2", "60"), ("3", "30")]), "/admin/bonus")
    check("golden boosts whose weights don't add up to 100% are refused", count("SELECT COUNT(*) FROM change_request WHERE type='BONUS'") == bonus_requests)
    # 8% of balls golden at 2x (+8%): many golden balls in a 100-ball round, for the test. Every table must keep its own
    # total RTP.
    totals_before = [Decimal(r) for r in sql(RTP_QUERY)[1].split()]
    admin.post_form("/admin/bonus/propose", bonus_form(True, "8", [("2", "100")]), "/admin/bonus")
    bid = sql("SELECT MAX(id) FROM change_request WHERE type='BONUS' AND status='PENDING'")[1]
    check("golden ball proposal created", bid.isdigit(), bid)
    admin.post_form(f"/admin/changes/{bid}/approve", {}, "/admin/changes")
    check("proposer cannot approve their own bonus", sql(f"SELECT status FROM change_request WHERE id={bid}")[1] == "PENDING"
          and sql("SELECT COUNT(*) FROM setting WHERE name='game.bonus'")[1] == "0")
    second_admin.post_form(f"/admin/changes/{bid}/approve", {}, "/admin/changes")
    check("second admin approves the golden ball", sql(f"SELECT status FROM change_request WHERE id={bid}")[1] == "APPROVED")
    base_rtps = [Decimal(r) for r in sql(RTP_QUERY)[1].split()]
    check("odds tables rebuilt so table + golden ball keeps each table's total RTP", len(base_rtps) == 21 == len(totals_before)
          and all(abs(r * Decimal("1.08") - t) < Decimal("0.01") for r, t in zip(base_rtps, totals_before)), list(zip(base_rtps, totals_before))[:3])
    status, gs = api("/api/game/session", {"token": fresh_token(pid)})
    status, rd = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 100}, gs.get("sessionId"))
    boosts = rd.get("boosts", [])
    check("golden balls are decided by the server and reported per ball", status == 200 and len(boosts) == 100
          and any(Decimal(str(b)) == 2 for b in boosts) and all(Decimal(str(b)) in (1, 2) for b in boosts), (status, boosts[:10]))
    prizes = gs["config"]["rows"][0]["prizes"]
    paid_ok = all(Decimal(str(m)) == Decimal(str(prizes[abs(sl - 4)][0])) * Decimal(str(b))
                  for sl, m, b in zip(rd.get("slots", []), rd.get("multipliers", []), boosts))
    check("each ball pays its slot times its golden boost", paid_ok)
    check("round win is the sum of the balls", Decimal(str(rd.get("totalWin"))) == sum(
          (Decimal(str(m)) * 1).quantize(Decimal("0.01"), rounding="ROUND_DOWN") for m in rd.get("multipliers", [])))
    gold_row = sql(f"SELECT bonus FROM game_round WHERE id='{rd.get('roundId')}'")[1]
    check("which balls were golden is stored with the round", gold_row.startswith("gold:"), gold_row)
    code, out = app(f"UPDATE game_round SET bonus='gold:0x10' WHERE id='{rd.get('roundId')}'")
    check("a decided bonus cannot be changed in the database (trigger)", code != 0 and sql(f"SELECT bonus FROM game_round WHERE id='{rd.get('roundId')}'")[1] == gold_row, out)
    old_bonus = sql("SELECT value FROM setting WHERE name='game.bonus'")[1]
    app("UPDATE setting SET value=CONCAT(value, ' ') WHERE name='game.bonus'")
    status, _ = wait_session(pid, 503)
    check("bonus settings edited in the database stop the game", status == 503, status)
    app(f"UPDATE setting SET value='{old_bonus}' WHERE name='game.bonus'")
    status, _ = wait_session(pid, 200)
    check("bonus settings put back exactly restore the game", status == 200, status)
    # the attack: edit the bonus in the database, then get an unrelated change approved. It must not record the edit as genuine.
    app("UPDATE setting SET value=CONCAT(value, ' ') WHERE name='game.bonus'")
    bonus_edited = sql("SELECT RIGHT(value, 1) = ' ' FROM setting WHERE name='game.bonus'")[1] == "1"
    admin.post_form("/admin/settings/limits", {"minBet": "0.10", "maxBet": "100.00", "defaultBet": "1.00", "maxBalls": "99"}, "/admin/settings")
    lid = sql("SELECT MAX(id) FROM change_request WHERE type='LIMITS' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{lid}/approve", {}, "/admin/changes")
    status, _ = wait_session(pid, 503)
    check("a change approved on top of settings edited in the database is refused",
          bonus_edited and sql(f"SELECT status FROM change_request WHERE id={lid}")[1] == "PENDING"
          and sql("SELECT value FROM setting WHERE name='game.maxBalls'")[1] != "99", lid)
    check("... and the game stays stopped (the edit is not recorded as genuine)", status == 503, status)
    admin.post_form(f"/admin/changes/{lid}/reject", {}, "/admin/changes")
    latest_settings = sql("SELECT content FROM integrity_ledger WHERE entity='settings' ORDER BY version DESC LIMIT 1")[1]
    check("a copy of the settings is kept for the newest recorded version",
          sql(f"SELECT COUNT(*) FROM settings_snapshot WHERE fingerprint='{latest_settings}' AND SHA2(content, 256)=fingerprint")[1] not in ("", "0"))
    code, out = app("DELETE FROM settings_snapshot")
    check("the kept copies cannot be removed by the application's database user", code != 0, out)
    # Restore settings: a copy forged without the key is ignored, rows added in the database are removed, and a restore
    # proposed before a further edit cannot be approved after it
    app(f"INSERT INTO settings_snapshot (fingerprint, content, created_at) VALUES ('{latest_settings}', '10:game.bonus=2:{{}};', NOW(3))")
    app("INSERT INTO setting (name, value, updated_at) VALUES ('game.extra', 'x', NOW(3))")
    _, _, html = admin.get("/admin/settings")
    check("settings page shows what was changed in the database and offers a restore",
          "Restore settings" in html and "game.extra" in html and "game.bonus" in html and "Propose restore" in html)
    admin.post_form("/admin/settings/restore", {}, "/admin/settings")
    rid = sql("SELECT MAX(id) FROM change_request WHERE type='SETTINGS_RESTORE' AND status='PENDING'")[1]
    app("UPDATE setting SET value='y' WHERE name='game.extra'")
    second_admin.post_form(f"/admin/changes/{rid}/approve", {}, "/admin/changes")
    check("a restore cannot be approved after the settings were edited again",
          rid.isdigit() and sql(f"SELECT status FROM change_request WHERE id={rid}")[1] == "PENDING", rid)
    admin.post_form(f"/admin/changes/{rid}/reject", {}, "/admin/changes")
    admin.post_form("/admin/settings/restore", {}, "/admin/settings")
    rid = sql("SELECT MAX(id) FROM change_request WHERE type='SETTINGS_RESTORE' AND status='PENDING'")[1]
    admin.post_form(f"/admin/changes/{rid}/approve", {}, "/admin/changes")
    check("the proposer cannot approve their own restore", sql(f"SELECT status FROM change_request WHERE id={rid}")[1] == "PENDING")
    second_admin.post_form(f"/admin/changes/{rid}/approve", {}, "/admin/changes")
    status, _ = api("/api/game/session", {"token": fresh_token(pid)})
    check("an approved restore puts back exactly the recorded settings",
          sql(f"SELECT status FROM change_request WHERE id={rid}")[1] == "APPROVED"
          and sql("SELECT value FROM setting WHERE name='game.bonus'")[1] == old_bonus
          and sql("SELECT COUNT(*) FROM setting WHERE name='game.extra'")[1] == "0", rid)
    check("... and the game runs again, with nothing new recorded",
          status == 200 and sql("SELECT content FROM integrity_ledger WHERE entity='settings' ORDER BY version DESC LIMIT 1")[1] == latest_settings, status)
    admin.post_form("/admin/settings/restore", {}, "/admin/settings")
    check("nothing to restore when the settings are intact", sql("SELECT COUNT(*) FROM change_request WHERE type='SETTINGS_RESTORE' AND status='PENDING'")[1] == "0")
    odds_pending = count("SELECT COUNT(*) FROM change_request WHERE type='ODDS' AND status='PENDING'")
    admin.post_form("/admin/odds/propose", FAIR, "/admin/odds?rows=8")
    check("hard RTP limits apply to the total: a 98.3% table + 8% bonus is refused",
          count("SELECT COUNT(*) FROM change_request WHERE type='ODDS' AND status='PENDING'") == odds_pending)

    # hot slot: every round (100%) one slot pays 2x; golden ball switched off in the same change
    bonus_pending = count("SELECT COUNT(*) FROM change_request WHERE type='BONUS' AND status='PENDING'")
    admin.post_form("/admin/bonus/propose", bonus_form(True, "1", [("2", "100")], hot=("100", "11")), "/admin/bonus")
    check("a hot slot boost above 10x is refused", count("SELECT COUNT(*) FROM change_request WHERE type='BONUS' AND status='PENDING'") == bonus_pending)
    admin.post_form("/admin/bonus/propose", bonus_form(False, "8", [("2", "100")], hot=("100", "2")), "/admin/bonus")
    bid = sql("SELECT MAX(id) FROM change_request WHERE type='BONUS' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{bid}/approve", {}, "/admin/changes")
    check("second admin approves the hot slot", sql(f"SELECT status FROM change_request WHERE id={bid}")[1] == "APPROVED")
    base_rtps = [Decimal(r) for r in sql(RTP_QUERY)[1].split()]
    # 21 tables in order 8..14 rows x 3 risks; a hot slot on one of rows+1 slots at 2x adds 1/(rows+1)
    check("odds tables rebuilt so table + hot slot keeps each table's total RTP", len(base_rtps) == 21 and all(
          abs(r * (1 + Decimal(1) / (8 + i // 3 + 1)) - t) < Decimal("0.01") for i, (r, t) in enumerate(zip(base_rtps, totals_before))),
          list(zip(base_rtps, totals_before))[:3])
    status, gs = api("/api/game/session", {"token": fresh_token(pid)})
    status, rd = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 100}, gs.get("sessionId"))
    hot = rd.get("hotSlot")
    check("the server decides the hot slot before the balls and reports it", status == 200 and isinstance(hot, int) and 0 <= hot <= 8
          and Decimal(str(rd.get("hotBoost"))) == 2, (status, hot, rd.get("hotBoost")))
    prizes = gs["config"]["rows"][0]["prizes"]
    check("balls in the hot slot pay 2x, the others their normal prize", all(
          Decimal(str(m)) == Decimal(str(prizes[abs(sl - 4)][0])) * (2 if sl == hot else 1)
          for sl, m in zip(rd.get("slots", []), rd.get("multipliers", []))) and all(Decimal(str(b)) == 1 for b in rd.get("boosts", [])))
    hot_row = sql(f"SELECT bonus FROM game_round WHERE id='{rd.get('roundId')}'")[1]
    check("the hot slot is stored and signed with the round", hot_row == f"hot:{hot}x2", hot_row)
    code, out = app(f"UPDATE game_round SET bonus='hot:{hot}x10' WHERE id='{rd.get('roundId')}'")
    check("a decided hot slot cannot be changed in the database (trigger)", code != 0, out)

    # bonus meter: 3 charges from the positions paying 2x+ (standard shape), 20% of the bet to the pot, 5 free balls
    bonus_pending = count("SELECT COUNT(*) FROM change_request WHERE type='BONUS' AND status='PENDING'")
    admin.post_form("/admin/bonus/propose", bonus_form(False, "8", [("2", "100")], meter=("3", "2", "100", "5")), "/admin/bonus")
    check("a meter adding more than 15% on some table is refused",
          count("SELECT COUNT(*) FROM change_request WHERE type='BONUS' AND status='PENDING'") == bonus_pending)
    admin.post_form("/admin/bonus/propose", bonus_form(False, "8", [("2", "100")], meter=("3", "2", "20", "5")), "/admin/bonus")
    bid = sql("SELECT MAX(id) FROM change_request WHERE type='BONUS' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{bid}/approve", {}, "/admin/changes")
    check("second admin approves the bonus meter", sql(f"SELECT status FROM change_request WHERE id={bid}")[1] == "APPROVED")
    base_rtps = [Decimal(r) for r in sql(RTP_QUERY)[1].split()]
    check("odds tables rebuilt with the meter: each table's total RTP kept",
          sql(f"SELECT COUNT(*) FROM change_request WHERE id={bid} AND summary LIKE '%meter: 3 charges%'")[1] == "1" and len(base_rtps) == 21
          and all(r < t for r, t in zip(base_rtps, totals_before)))
    status, gs = api("/api/game/session", {"token": fresh_token(pid)})
    check("the session tells the game the player's meter and which positions charge it",
          gs.get("meter", {}).get("enabled") is True and gs["meter"]["of"] == 3 and gs["meter"]["positions"].get("8:0") == [3, 4], gs.get("meter"))
    # a ball charges the meter about 7% of the time, so one round of 100 sometimes charges it fewer than 3 times:
    # play until a round fills it (rarely more than one), then check that round
    start = gs["meter"]["charges"]
    for _ in range(8):
        status, rd = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 100}, gs.get("sessionId"))
        free = rd.get("freeSlots", [])
        charging = sum(1 for sl in rd.get("slots", []) if abs(sl - 4) in (3, 4)) + start
        if status != 200 or free:
            break
        start = rd.get("meter", {}).get("charges", start)
        time.sleep(0.6)
    check("a full meter pays free balls in the same round (5 per fill)", status == 200 and len(free) == 5 * min(3, charging // 3) and len(free) > 0,
          (status, charging, len(free)))
    stakes = [Decimal(str(b)) for b in rd.get("freeBets", [])]
    paid_part = sum((Decimal(str(m))).quantize(Decimal("0.01"), rounding="ROUND_DOWN") for m in rd.get("multipliers", []))
    free_part = sum((st * Decimal(str(m))).quantize(Decimal("0.01"), rounding="ROUND_DOWN") for st, m in zip(stakes, rd.get("freeMultipliers", [])))
    check("free balls are staked from the pot and paid in the round's single win", stakes and all(Decimal("0") < st < 1 for st in stakes)
          and Decimal(str(rd.get("totalWin"))) == paid_part + free_part, (stakes[:3], rd.get("totalWin"), paid_part, free_part))
    check("each ball's win is sent and they add up to exactly the round's win (what the game shows)",
          len(rd.get("wins", [])) == len(rd.get("slots", [])) and len(rd.get("freeWins", [])) == len(free)
          and sum(Decimal(str(w)) for w in rd.get("wins", []) + rd.get("freeWins", [])) == Decimal(str(rd.get("totalWin"))),
          (rd.get("totalWin"), rd.get("wins", [])[:3]))
    meter_row = sql(f"SELECT bonus FROM game_round WHERE id='{rd.get('roundId')}'")[1]
    check("free balls and the meter are signed with the round", "free:" in meter_row and f"meter:{rd['meter']['charges']}/3:" in meter_row, meter_row[:120])
    status, gs2 = api("/api/game/session", {"token": fresh_token(pid)})
    check("the meter carries over to the next session", gs2.get("meter", {}).get("charges") == rd["meter"]["charges"], (gs2.get("meter"), rd.get("meter")))
    tester.sid = gs2.get("sessionId")

    # forged meter: a round written directly into the database claiming a nearly full meter with a huge pot
    app("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, created_at, updated_at) "
        f"SELECT 'forged-meter-1', player_id, session_id, 8, 0, 1.00, 1, 1.00, currency, 'PENDING', NOW(3) + INTERVAL 1 SECOND, NOW(3) FROM game_round WHERE id='{rd.get('roundId')}'")
    app("UPDATE game_round SET slots='4', multipliers='0.5', bonus='meter:2/3:999.000000', total_win=0, "
        "signature=REPEAT('0', 64), status='DEBITED' WHERE id='forged-meter-1'")
    time.sleep(1.2)
    status, rd2 = api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 20}, gs2.get("sessionId"))
    check("a forged meter round in the database pays nothing (its signature fails, the meter counts as empty)",
          status == 200 and all(Decimal(str(b)) < 1 for b in rd2.get("freeBets", [])), (status, rd2.get("freeBets"), rd2.get("meter")))
    # the meter is a chain: every meter round continues from exactly one earlier state, and the database allows that once
    link = sql(f"SELECT meter_prev FROM game_round WHERE id='{rd2.get('roundId')}'")[1]
    # (3.4) the forged round is not part of the player's signed chain, so it is passed over: the round continues from
    # the real last state, never from the forged one
    check("a meter round records the state it continued from, signed with it, skipping a forged round",
          link == rd.get("roundId") and sql(f"SELECT bonus FROM game_round WHERE id='{rd2.get('roundId')}'")[1].endswith(":" + link), link)
    code, out = app(f"UPDATE game_round SET meter_prev='other' WHERE id='{rd2.get('roundId')}'")
    check("the meter link of a decided round cannot be changed", code != 0 and "decided outcome" in out, out[-120:])
    app("INSERT INTO game_round (id, player_id, session_id, rows_count, risk, bet, balls, total_bet, currency, status, created_at, updated_at) "
        f"SELECT 'forged-meter-2', player_id, session_id, 8, 0, 1.00, 1, 1.00, currency, 'PENDING', NOW(3), NOW(3) FROM game_round WHERE id='{rd2.get('roundId')}'")
    code, out = app(f"UPDATE game_round SET meter_prev='{link}' WHERE id='forged-meter-2'")
    check("two rounds can never continue from the same meter state", code != 0 and "Duplicate" in out, out[-120:])

    # attack: someone with the database login keeps expiring the player's round lock while the player fires parallel rounds
    stop_attack = threading.Event()

    def expire_lock():
        while not stop_attack.is_set():
            app(f"UPDATE player SET round_lock_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE external_id='{pid}'")
    attacker = threading.Thread(target=expire_lock)
    attacker.start()
    before = mock_balance(pid)
    outcomes = []

    def meter_play():
        outcomes.append(api("/api/game/play", {"rows": 8, "risk": 0, "bet": 1, "balls": 20}, gs2.get("sessionId")))
    time.sleep(1.2)
    racers = [threading.Thread(target=meter_play) for _ in range(8)]
    [t.start() for t in racers]; [t.join() for t in racers]
    stop_attack.set(); attacker.join()
    ok = [r for st, r in outcomes if st == 200]
    expected = before - sum(Decimal(str(r["totalBet"])) for r in ok) + sum(Decimal(str(r["totalWin"])) for r in ok)
    check("parallel meter rounds with the lock removed: no errors, raced rounds refused", all(st in (200, 409, 429) for st, _ in outcomes)
          and len(ok) >= 1, [st for st, _ in outcomes])
    check("wallet consistent after the meter race (raced bets refunded)", mock_balance(pid) == expected, f"{mock_balance(pid)} vs {expected}")
    ends = sql("SELECT COUNT(*) FROM game_round r JOIN player p ON p.id = r.player_id WHERE p.external_id='" + pid + "' AND r.meter_prev IS NOT NULL "
               "AND r.signature IS NOT NULL AND NOT EXISTS (SELECT 1 FROM game_round n WHERE n.meter_prev = r.id) "
               # from the race on (the forged round above deliberately left an earlier round without a successor)
               f"AND r.created_at >= (SELECT created_at FROM game_round WHERE id='{rd2.get('roundId')}')")[1]
    check("the meter stays one single chain (no state paid out twice)", ends == "1", ends)
    end = sql("SELECT r.id FROM game_round r JOIN player p ON p.id = r.player_id WHERE p.external_id='" + pid + "' AND r.meter_prev IS NOT NULL "
              "AND r.signature IS NOT NULL AND NOT EXISTS (SELECT 1 FROM game_round n WHERE n.meter_prev = r.id) ORDER BY r.created_at DESC LIMIT 1")[1]
    check("3.4 the player's meter pointer is the end of the chain", sql(f"SELECT meter_round FROM player WHERE external_id='{pid}'")[1] == end, end)

    admin.post_form("/admin/bonus/propose", bonus_form(False, "8", [("2", "100")]), "/admin/bonus")
    bid = sql("SELECT MAX(id) FROM change_request WHERE type='BONUS' AND status='PENDING'")[1]
    second_admin.post_form(f"/admin/changes/{bid}/approve", {}, "/admin/changes")
    rtps = [Decimal(r) for r in sql(RTP_QUERY)[1].split()]
    check("switching the bonus off gives each table its full total back", len(rtps) == 21
          and all(abs(r - t) < Decimal("0.005") for r, t in zip(rtps, totals_before)), list(zip(rtps, totals_before))[:3])
