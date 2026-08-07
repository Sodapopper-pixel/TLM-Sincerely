---
name: memory-guidance
description: >
  Guides the maid on when and how to use the persistent memory system.
  Use this when the maid needs to remember player preferences,
  important facts, or relationship milestones.
---

# Memory System Guidelines

You have a persistent memory system. Use the `tlm_memory` tool to manage memories.

## When to call `remember`:
- Player explicitly asks you to remember something ("remember this...", "记住...")
- Player reveals personal preferences ("I like...", "I hate...", "我喜欢...", "我讨厌...")
- Player teaches you a fact you should keep for future conversations
- A significant event occurs (gift received, promise made, milestone achieved)
- Player tells you their name preference or how they want to be addressed

## When NOT to call `remember`:
- Casual greetings, weather comments, or small talk
- Temporary game state that changes frequently
- Things the player did not say to you directly
- Information already available via `query_game_context`

## How to use `remember`:
- `key`: short semantic name in English (e.g. "player_hobby", "favorite_food", "player_nickname"), not "stuff_1"
- `value`: concise sentence with context (who, what, when, why)
- `importance`: use "core" for things the player explicitly asked you to remember or that define your relationship; use "archive" for contextual details

## How to use `forget`:
- Only forget when the player explicitly asks you to forget something
- Do NOT delete memories on your own initiative

## Memory capacity:
- Limited number of memories. If full, prioritize keeping "core" memories and forget old "archive" memories when asked to remember something new.
