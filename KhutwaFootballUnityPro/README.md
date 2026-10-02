# Khutwa Football — Professional Unity Track

This is the professional rebuild of **مباراة المعرفة**. It deliberately replaces the earlier WebGL prototype path.

## Engine
- Unity 6.3 LTS target: 6000.3.25f1
- Mobile-first 3D architecture
- URP target
- Android first, iOS later

## Core rule
There is **no manual football control**. Knowledge is the controller:
- Correct answer → pass / continue attack
- Wrong answer → interception / turnover
- Reach final attacking step → shooting question
- Correct shot answer → high-quality scoring chance
- Goal/save animation → restart from centre

## Implemented source architecture
- Match state machine
- Two 6-player teams
- Pass route logic
- Ball flight with arc/spin
- Turnovers and interceptions
- Shot resolution
- Goalkeeper reactions
- Broadcast camera controller
- UI event bridge for question layer
- Score state

## Production visual gate
Do **not** call a build "final" until these production assets are installed:
1. Football humanoid player model(s), humanoid rig
2. Real football motion-capture animations: idle, jog, sprint, trap, short pass, long pass, tackle, shot, goalkeeper idle/dive/catch, celebration
3. Stadium/pitch environment
4. Arabic RTL UI font + RTL TextMeshPro
5. Crowd/audio/VFX

Recommended production animation source currently: AA Soccer Mega Animations Pack (308 humanoid mocap clips including player, goalkeeper and celebrations). The project code is designed so those clips can be mapped to Animator states without rewriting game logic.

## Folder architecture
Assets/KhutwaFootball/Scripts/
- Core/       match state & match director
- Gameplay/   players, teams, ball, goalkeeper
- Camera/     broadcast camera behavior
- Quiz/       question data
- UI/         question/score event bridge

## Acceptance sequence for the first real vertical slice
Kickoff question → possession → animated receive/pass → second question → pass → wrong answer → tackle/turnover → counterattack → shooting question → shot → goalkeeper save or goal → celebration → centre restart.

That sequence is the minimum bar before exporting the first professional APK.
