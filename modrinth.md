# Village Board

**Turn a crowd of villagers into a real town.** Place a **Town Hall Board**, mark your borders with **Boundary Stones**,
and keep track of every resident: who was born, who died and how, who works where, who sleeps where, and who married whom.

Made for big villages and trading halls where you've long lost track of who's who.

> Requires **Fabric API**. Install the mod on **both the server and every client**.

---

## Getting started

1. **Craft and place a Town Hall Board.** Your village is founded and the villagers around it are registered.
   Rename the board in an anvil before placing it to give your village a name.
2. **Place Boundary Stones** around the village. Each stone becomes a corner of the territory (up to 64 stones, within
   256 blocks of the board). With fewer than 3 stones, the territory is a provisional 48-block circle around the board.
3. **Hold a stone or the board** to see the borders drawn on the ground with particles. A message pops up whenever you
   enter or leave a village.

## The Town Hall Board

Right-click the board to open it.

### 📰 Gazette
The village newspaper, written by itself: births (with the parents), deaths (with the cause), new jobs and resignations,
arrivals, departures and moves between villages, villagers turned into zombies or witches, cures, marriages and
divorces, beds running out, territory changes and more.

### 👥 Villagers
Every resident, sorted by category: children, homeless, unemployed, each profession, nitwits. Click a villager to open
their sheet: animated portrait, profession and level, health, experience, position, workstation, bed, date of birth or
arrival, family and spouse. From the sheet you can:

- **Rename**: works like a name tag.
- **Locate**: the villager glows through walls and a compass appears in your action bar.
- **Lock**: freezes their profession, even if their workstation disappears. No more librarians losing their trades.
- **Reset**: the villager leaves their job, forgets their trades and looks for a new one.

### 🌳 Families
A family tree of the whole village, one block per family and one row per generation. Married couples, widows,
divorces and the dead stay on record. Zoom, drag, and click any villager to open their sheet.

### 📋 Needs
What your village is missing, sorted from urgent to minor: homeless villagers with no free bed, no free bed at all
(births are blocked), unemployed villagers with no workstation (with suggestions for missing professions), no farmer,
no iron golem, no bell… Click a need to jump straight to where you can fix it.

### 🗺️ Territory
An interactive map painted from the actual terrain: your territory, neighbouring villages, boundary stones, beds
(free or taken), workstations (free or taken) and every villager coloured by status. Hover for names, click to pin
a villager, bed or workstation and see its links. The legend doubles as a filter: show only the homeless, only free
beds, and so on.

## Paperwork

| Item | What it does |
|---|---|
| **Work Contract** | Right-click a villager, then a workstation. They take that job (switching profession if they have no experience yet) and keep that exact workstation for good. |
| **Housing Lease** | Right-click a villager, then a bed. That bed is theirs: if they lose it, they're put back within 2 seconds. Works for children and nitwits too. |
| **Marriage Certificate** | Right-click a villager, then their future spouse. |

If the workstation or bed is destroyed, the link is broken and the gazette reports it.

## Families and marriages

The mod keeps a **civil register**: every birth records both parents, and records survive death and departure.

- Two single villagers who have a child together get married automatically.
- Married villagers only breed with their spouse.
- No children or marriage between parents and children or between siblings (half-siblings included).
- A marriage ends with the death of a spouse (who can then remarry) or with a divorce from the villager's sheet.

## Talking to villagers

Right-clicking a villager now makes them **say something** first: a dialogue box at the bottom of the screen, with their
face, name and profession, text typed letter by letter and a little villager mumble. Right-click again within
10 seconds to trade, or sneak + right-click to trade right away. Name tags, leads and spawn eggs work as usual.

Lines depend on who the villager is and on the situation: profession, child, homeless, married, widowed, parent,
night, rain… They live in `config/villageboard/dialogues.txt` on the server and reload as soon as you save the file.
Lines can include the player's name, the villager's name, the village or their spouse.

## Trade card

When you open a villager's trades, a small card appears next to the trading window: their face, name, profession,
whether they have a bed, and their family.

## Smarter villagers

- **Long-range beds**: vanilla villagers only look for beds within 48 blocks, and a baby with no bed means a failed birth.
  Villagers can now find free beds anywhere in the village territory, up to 140 blocks away, as long as they can
  walk there.
- A villager seen outside the borders for 60 seconds is marked as having left the village.
- If the board is broken, the village is kept: place a new board on its territory to bind it again.

## Crafting

- **Town Hall Board**: paper, bell, paper / 3 planks / stick, empty, stick
- **Boundary Stone** (×4): yellow dye / chiseled stone bricks / stone brick wall
- **Work Contract** (shapeless): paper + feather + ink sac
- **Housing Lease** (shapeless): paper + feather + ink sac + wool
- **Marriage Certificate** (shapeless): paper + feather + ink sac + gold nugget

## Configuration and commands

`config/villageboard.json`: provisional territory radius, maximum stone distance, census interval, gazette length,
glow duration, departure delay, job sounds, and who may manage villagers (`"managers": "everyone"` or `"founder"`).

Anyone can read the board; actions must be done within 8 blocks of it.

Operator commands:
- `/villageboard list`: list all villages
- `/villageboard info <id>`: population, homeless, unemployed, beds, workstations, bells, golems
- `/villageboard remove <id>`: dissolve a village

## Details

- Minecraft **26.3**, Fabric Loader 0.19.3+, Java 25
- Translated into **English** and **French**
- Village data is saved in the world folder (`<world>/villageboard/`)
