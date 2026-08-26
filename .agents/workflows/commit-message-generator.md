---
description: workflow to generate a commit message that fits the project's existing style based strictly on git changes.
---

# Commit Message Generator

Use this workflow to generate a commit message that fits the project's existing style.

## Core Rules
- **Ignore Conversation Context**: Base the commit message **strictly and exclusively** on actual git changes and diffs. Do NOT rely on previous chat context, user conversational prompts, or prior discussion to infer what was changed.
- **Pre-Push Scope**: Analyze all uncommitted changes (staged and unstaged) as well as unpushed commits relative to the upstream branch.

## Project Commit Style
- **Language**: English.
- **Format**: Simple, imperative, or past tense (e.g., "Add feature", "Fix bug", "Update changelog").
- **Capitalization**: Start with a capital letter.
- **No Punctuation**: Do not end the subject line with a period.
- **Examples**:
  - `Update changelog`
  - `Add feedback form with canny integration`
  - `Fix tracking`
  - `Redesigned campaign details page`

## Workflow Steps

1. **Analyze Pre-Push Changes**
   - Check working tree status: `git status`
   - Inspect all unstaged and staged diffs: `git diff HEAD`
   - If comparing against upstream/remote, check unpushed commits and total changes: `git log @{u}..HEAD` / `git diff @{u}`
   - Read the actual code changes directly to understand the exact additions, deletions, and refactors.

2. **Generate Message Title**
   Create a concise summary of the changes conforming to the project style derived solely from the diffs.
   - Good: `Fix login redirection issue`
   - Bad: `fixed the login bug and cleaned up some code` (too verbose/informal)

3. **Generate Message Description**
   Create a concise bullet point list of changes based purely on what the code diff reflects. Only if some change is a complicated or breaking API change write 1-2 paragraphs about the changed code.

4. **Ask User Confirmation**
   Present the proposed commit title and description to the user, and ask if they would like you to execute the commit with this message. Only proceed with `git commit` if the user confirms.
