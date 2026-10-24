"""Schema checks. These do not call Ollama."""

import unittest

from agent import interpret


def reply(payload: str):
    def complete(_messages):
        return payload

    return complete


class AgentSchemaTest(unittest.TestCase):
    def test_call_strips_my_from_contact_name(self):
        result = interpret(
            "can you place a call to my mummy",
            complete=reply(
                '{"action":"call","contact_name":"my mummy","reply":"Looking up mummy."}'
            ),
        )
        self.assertEqual(result["action"], "call")
        self.assertEqual(result["contact_name"], "mummy")

    def test_disable_alarm_is_only_the_action(self):
        result = interpret(
            "disable alarm",
            complete=reply('{"action":"disable_alarm","reply":"There is no alarm set."}'),
        )
        self.assertEqual(result["action"], "disable_alarm")
        self.assertNotIn("message", result)

    def test_follow_up_time_sees_history_and_pending(self):
        seen = {}

        def complete(messages):
            seen["messages"] = messages
            return '{"action":"set_alarm","hour":7,"minute":30,"label":"Alarm","reply":"Setting it."}'

        result = interpret(
            "7:30",
            pending={"action": "set_alarm"},
            history=[{"role": "assistant", "text": "What time should I set the alarm?"}],
            complete=complete,
        )
        self.assertEqual(result["hour"], 7)
        self.assertEqual(result["minute"], 30)
        self.assertTrue(any("What time" in item["content"] for item in seen["messages"]))
        self.assertIn("set_alarm", seen["messages"][-1]["content"])

    def test_repairs_invalid_json_once(self):
        calls = {"n": 0}

        def complete(_messages):
            calls["n"] += 1
            if calls["n"] == 1:
                return "sorry, not json"
            return '{"action":"whatsapp","contact_name":"mummy","message":"helo","reply":"Opening WhatsApp."}'

        result = interpret("open whatsapp and msg mummy helo", complete=complete)
        self.assertEqual(calls["n"], 2)
        self.assertEqual(result["action"], "whatsapp")
        self.assertEqual(result["contact_name"], "mummy")
        self.assertEqual(result["message"], "helo")

    def test_model_down(self):
        def complete(_messages):
            raise RuntimeError("down")

        result = interpret("hello", complete=complete)
        self.assertEqual(result["action"], "clarify")
        self.assertIn("ollama serve", result["message"])

    def test_typed_number_dials_without_asking_the_model(self):
        def complete(_messages):
            raise AssertionError("the model should not see a typed number")

        result = interpret(
            "9876543210",
            pending={"action": "call", "contact_name": "mummy"},
            history=[{"role": "assistant", "text": "I can't find mummy. Type the phone number."}],
            complete=complete,
        )
        self.assertEqual(result["action"], "dial")
        self.assertEqual(result["number"], "9876543210")

    def test_nine_digits_are_not_dialed(self):
        def complete(_messages):
            raise AssertionError("a short number must not be dialed")

        result = interpret(
            "123456789",
            pending={"action": "call", "contact_name": "gopi"},
            complete=complete,
        )
        self.assertEqual(result["action"], "clarify")
        self.assertIn("10 digits", result["message"])
        self.assertEqual(result["pending"]["action"], "call")

    def test_model_dial_with_nine_digits_is_rejected(self):
        result = interpret(
            "please call this number 123456789",
            complete=reply(
                '{"action":"dial","number":"123456789","reply":"Calling 123456789."}'
            ),
        )
        self.assertEqual(result["action"], "clarify")
        self.assertIn("9 digits", result["message"])

    def test_spaced_number_keeps_the_country_code(self):
        result = interpret(
            "+91 98765 43210",
            pending={"action": "call", "contact_name": "mummy"},
            complete=lambda _messages: "should not be called",
        )
        self.assertEqual(result["number"], "+919876543210")

    def test_number_inside_a_sentence_finishes_whatsapp(self):
        result = interpret(
            "the number is 98765 43210",
            pending={"action": "whatsapp", "contact_name": "mummy", "message": "helo"},
            complete=lambda _messages: "should not be called",
        )
        self.assertEqual(result["action"], "whatsapp")
        self.assertEqual(result["number"], "9876543210")
        self.assertEqual(result["message"], "helo")

    def test_does_not_keep_example_name_when_user_named_someone_else(self):
        calls = {"n": 0}

        def complete(_messages):
            calls["n"] += 1
            if calls["n"] == 1:
                return '{"action":"call","contact_name":"mummy","reply":"Looking up mummy."}'
            return '{"action":"whatsapp","contact_name":"gopi ben","message":"hello","reply":"Opening WhatsApp."}'

        result = interpret("whatsapp gopi ben hello", complete=complete)
        self.assertEqual(calls["n"], 2)
        self.assertEqual(result["contact_name"], "gopi ben")

    def test_selected_text_is_explained_in_hindi(self):
        seen = {}

        def complete(messages):
            seen["system"] = messages[0]["content"]
            seen["user"] = messages[-1]["content"]
            return '{"action":"explain","explanation":"Woh kal aayengi."}'

        result = interpret(
            "I will come tomorrow",
            pending={"action": "explain"},
            complete=complete,
        )
        self.assertEqual(result["action"], "explain")
        self.assertEqual(result["explanation"], "Woh kal aayengi.")
        self.assertEqual(seen["user"], "I will come tomorrow")
        self.assertIn("Hindi", seen["system"])
        self.assertIn("only the words of that message", seen["system"])
        self.assertIn("Do not mention a phone number", seen["system"])
        self.assertNotIn("contact_name", seen["system"])

    def test_explain_uses_the_message_and_drops_the_number(self):
        seen = {}

        def complete(messages):
            seen["user"] = messages[-1]["content"]
            return '{"action":"explain","explanation":"Woh kal ghar aane ko keh rahi hain."}'

        result = interpret(
            "Please come home tomorrow. My number is +91 98765 43210",
            pending={"action": "explain"},
            complete=complete,
        )
        self.assertEqual(result["explanation"], "Woh kal ghar aane ko keh rahi hain.")
        self.assertEqual(seen["user"], "Please come home tomorrow.")
        self.assertNotIn("98765", seen["user"])
        self.assertNotIn("number", seen["user"].lower())

    def test_a_bare_number_is_not_explained(self):
        def complete(_messages):
            raise AssertionError("a phone number is not the message")

        result = interpret("+91 98765 43210", pending={"action": "explain"}, complete=complete)
        self.assertEqual(result["action"], "explain")
        self.assertIn("baat nahi", result["explanation"])
        self.assertNotIn("phone", result["explanation"].lower())

    def test_blank_selection_skips_the_model(self):
        def complete(_messages):
            raise AssertionError("blank text must not be explained")

        result = interpret("  ", pending={"action": "explain"}, complete=complete)
        self.assertEqual(result["action"], "explain")
        self.assertIn("likhai", result["explanation"])

    def test_explain_failure_stays_in_hindi(self):
        def complete(_messages):
            raise RuntimeError("unavailable")

        result = interpret("See you at 5", pending={"action": "explain"}, complete=complete)
        self.assertEqual(result["action"], "explain")
        self.assertIn("nahi", result["explanation"])

    def test_chat_explain_opens_the_screen_instead_of_inventing_hindi(self):
        result = interpret(
            "samjhao ye message",
            complete=reply(
                '{"action":"explain","explanation":"kal aana","reply":"Box banao."}'
            ),
        )
        self.assertEqual(result["action"], "explain_screen")
        self.assertNotIn("explanation", result)

    def test_rejects_alarm_out_of_range(self):
        result = interpret(
            "set an alarm",
            complete=reply('{"action":"set_alarm","hour":25,"minute":0,"reply":"no"}'),
        )
        self.assertEqual(result["action"], "clarify")


if __name__ == "__main__":
    unittest.main()
