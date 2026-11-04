"""Client-owned assistant tool loop: the gateway calls no UIT endpoints or tools."""
import json
from pathlib import Path

CONTRACT = json.loads(Path(__file__).with_name("assistant_contract.json").read_text())


def request(data):
    if set(data) != {"version", "mode", "input", "step", "language"}:
        raise ValueError("Invalid assistant fields")
    if type(data["step"]) is not int or not 0 <= data["step"] < 6 or data["language"] not in {"English", "Vietnamese"}:
        raise ValueError("Invalid assistant step or language")
    items = data["input"]
    if not isinstance(items, list) or not 1 <= len(items) <= 80 or len(json.dumps(items, ensure_ascii=False)) > 110000:
        raise ValueError("Invalid assistant context")
    for item in items:
        if not isinstance(item, dict):
            raise ValueError("Invalid input item")
        kind = item.get("type")
        if "role" in item:
            if item["role"] not in {"user", "assistant"} or not isinstance(item.get("content"), (str, list)):
                raise ValueError("Invalid message role")
            content = item["content"]
            if isinstance(content, list):
                if not all(isinstance(part, dict) and part.get("type") == "output_text" and isinstance(part.get("text"), str) for part in content):
                    raise ValueError("Only text message parts are accepted")
        elif kind == "function_call":
            if item.get("name") not in {"search_announcements", "read_announcement"}:
                raise ValueError("Unknown assistant tool")
        elif kind not in {"function_call_output", "reasoning"}:
            raise ValueError("Unknown input type")
    return data


def payload(model, data):
    request(data)
    return {"model": model, "store": False,
            "instructions": CONTRACT["instructions"] + "\nRequested language: " + data["language"],
            "input": data["input"], "tools": CONTRACT["tools"],
            "tool_choice": "required" if data["step"] == 0 else "auto",
            "parallel_tool_calls": False, "max_output_tokens": 5000,
            "include": ["reasoning.encrypted_content"],
            "text": {"format": {"type": "json_schema", "name": "uit_assistant_answer", "strict": True, "schema": CONTRACT["schema"]}}}


def result(data):
    if not isinstance(data, dict) or data.get("status") != "completed" or not isinstance(data.get("model"), str):
        raise ValueError("Incomplete assistant output")
    output = data.get("output")
    if not isinstance(output, list) or not 1 <= len(output) <= 10:
        raise ValueError("Invalid assistant output")
    for item in output:
        if item.get("type") not in {"reasoning", "function_call", "message"}:
            raise ValueError("Invalid output type")
        if item.get("type") == "function_call" and item.get("name") not in {"search_announcements", "read_announcement"}:
            raise ValueError("Unknown assistant tool")
        if any(part.get("type") == "refusal" for part in item.get("content", [])):
            raise ValueError("Refused assistant output")
    if len(json.dumps(data, ensure_ascii=False).encode()) > 262144:
        raise ValueError("Assistant output too large")
    return data
