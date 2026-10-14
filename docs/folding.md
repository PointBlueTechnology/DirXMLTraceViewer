# Folding trace output: design note (#3)

Status: **proposed 2026-10-14.** Decided so far, on #3: web first, the desktop later, and no new
dependency. Section 6 lists what is still open.

The issue asks for controls that fold channel output by **policy set**, by **policy** and by
**XML**. A collapsed region shows one summary line in place of everything it holds, and a click
opens it again.

## 1. Where it is built

- **`trace-core`** works out the regions. Both viewers parse and highlight with this library, so a
  region means the same thing in each. The jump markers of #2 (`TraceMarker`) already name where
  the policy sets and policies begin.
- **The web Trace view** (DirXMLDevWeb) is the first to fold. Its list is virtual, so hiding the
  rows of a region is a filter on what it draws.
- **The desktop viewer** comes later. Its `JTextPane` cannot fold without a display model separate
  from the record buffer, or a library such as RSyntaxTextArea. #3 rules out the library, and the
  display model is a larger change to Find, Copy and trimming. The regions from `trace-core` stay
  ready for it.

## 2. The regions

The engine writes one thread's work at a time, but a live trace interleaves drivers, channels and
servers. A region therefore belongs to one **thread**: the server, the driver and the channel
(`ST`, `PT` or another tag) of the record that opens it. Only records of that thread can extend
or close it.

| Kind | Opens at | Holds | Closes before |
|---|---|---|---|
| **Policy set** | `Applying … policies.` | its policies, and anything the thread writes between them | the first record after the last policy's returned document that is not `Applying policy:` (the next set, `Submitting document to subscriber shim`, `End transaction`, `Pumping XDS to eDirectory` …) |
| **Policy** | `Applying policy: <name>.` (on `ST` or `PT`) | the rules, actions, queries and their results, then `Policy returned:` and the returned document | the thread's next record that has a header, after `Policy returned:` and its document |
| **XML** | the first line of an XML document in a message (`<nds`, or any root element) | the document's lines up to its root's end tag | the line after that end tag |

- A policy set holds its policies, and a policy holds its XML. Folding the set hides all of it.
- A region with nothing in it, such as a set whose next line closes it, is not foldable.
- **XML lines.** A trace file keeps a message's XML lines inside the same record, as lines of its
  text. A live stream sends them as separate records with no header. The XML region covers both:
  - line ranges inside one record, or
  - a run of records with no header that belong to one message.
- **Live regions.** In a live trace, a region whose end has not arrived yet is still open. It can be
  folded already, and the records that arrive into it stay hidden, with its count growing.

## 3. The library

`TraceFolds` is a stateful builder, fed record by record like `TraceParser`, and keeps the open
regions of each thread. For each record it gives:

- `opens`: the regions this record starts, each with an id, its kind, its parent region and a
  label (the set's or policy's name).
- `within`: the innermost region the record belongs to. Its ancestors come from the parent ids.
- `xml`: for a record whose text holds XML lines, the line ranges of each document (a trace file's
  case).

A region learns where it ends only later, so a viewer counts what it hides as the records arrive.
For a file, the server reads the whole file once and attaches the same fields to every record it
serves, in any page.

## 4. The web Trace view

- **Toggles.** The first line of a foldable region gets a toggle (▾ / ▸). When collapsed, it reads
  e.g. `▸ Applying event transformation policies. (3 policies, 120 lines)`.
- **Fold all / unfold all** for each kind sits in the filters row: policy sets, policies, XML.
- **Opening folds.** Find, a jump and go-to-time open any region that hides their target, so they
  never land on a hidden line.
- **Filters.** A region whose first line is filtered out has no toggle, and its lines show as they
  would without folding.
- **Remembering.** What is collapsed is remembered by region id while the records stay in memory.
  Pop-out has its own state.
- **Server JSON.** Each record from the server gains `fo` (the regions it opens: id, kind, parent,
  label), `fw` (its innermost region) and `fx` (the XML line ranges in its text).

## 5. Tests

- **`trace-core`.** Synthetic traces cover:
  - a set holding two policies, each with its returned document;
  - a veto;
  - a set closed by `Submitting document to subscriber shim`;
  - two drivers interleaved on one stream;
  - a file record holding its XML lines;
  - a live run of records with no header;
  - a region still open at the end of the input.
- **The web.** Page tests for visibility, smoke checks for the new fields on an uploaded file, and a
  browser check of fold, unfold, fold all, and Find or a jump into a folded region.

## 6. Decisions needed

1. **Interleaved lines.** In a live trace with several drivers, other threads' lines fall between
   the start and end of a region.
   - A. Fold only when the view is filtered to one driver and channel.
   - B. Fold the region's own thread. Other threads' lines stay visible where they fall; with one
     driver selected the region reads as one block.
   - C. Hide everything between the region's start and end, whatever thread it came from.

   **Recommended: B.** A hides a useful control behind a filter. C hides other drivers' work,
   which is what someone looking across drivers wants to see.
2. **XML granularity.** Whole documents first, or every element (nested folds inside an XML
   document)? **Recommended:** whole documents in this change, elements later if wanted. A
   document's element tree is far deeper than the set, policy and document levels.
3. **Starting state.** Everything open, or policy sets collapsed when a file is opened?
   **Recommended:** everything open, with *fold all* one click away. Live trace should never start
   hidden.
4. **Save and Copy.** Write the folded regions in full, or the summary lines as shown?
   **Recommended:** in full. Folding is a reading aid, and a saved trace should not lose lines.

## 7. Phases

| Phase | What | Release |
|---|---|---|
| F1 | `TraceFolds` in `trace-core`, with its tests | DirXMLTraceViewer 1.7.0 |
| F2 | The web Trace view: the new JSON fields, toggles, fold all, Find, jump and time opening folds | DirXMLDevWeb, after 1.7.0 |
| later | The desktop viewer, on the same regions | when the display model is reworked |
