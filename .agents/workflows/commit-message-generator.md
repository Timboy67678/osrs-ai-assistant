---
description: workflow to generate a commit message that fits the project's existing style.
---

# Commit Message Generator

Use this workflow to generate a commit message that fits the project's existing style.

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

1. **Analyze Changes**
   View all changes (staged and unstaged): `git diff HEAD`

2. **Generate Message Title**
   Create a concise summary of the changes conforming to the project style.
   - Good: `Fix login redirection issue`
   - Bad: `fixed the login bug and cleaned up some code` (too verbose/informal)

3. **Generate Message Description**
   Create concise bullet point list of changes. Only if some change is a complicated or breaking API change write 1-2 paragraphs about the changed code.

4. **Ask User Confirmation**
   Present the proposed commit title and description to the user, and ask if they would like you to execute the commit with this message. Only proceed with `git commit` if the user confirms.
