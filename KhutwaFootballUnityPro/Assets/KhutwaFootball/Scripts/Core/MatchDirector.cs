using System.Collections;
using System.Collections.Generic;
using KhutwaFootball.CameraSystem;
using KhutwaFootball.Gameplay;
using KhutwaFootball.Quiz;
using KhutwaFootball.UI;
using UnityEngine;

namespace KhutwaFootball.Core
{
    public sealed class MatchDirector : MonoBehaviour
    {
        [Header("Teams")]
        public TeamController blue;
        public TeamController red;

        [Header("Systems")]
        public BallMotor ball;
        public BroadcastCameraRig cameraRig;
        public MatchUIBridge ui;

        [Header("Questions")]
        public List<QuestionData> questions = new();

        [Header("Tuning")]
        public int passesBeforeShot = 3;
        public float passDuration = .72f;
        public float shotDuration = .62f;
        [Range(0,1)] public float correctShotGoalChance = .72f;

        public MatchState State { get; private set; } = MatchState.Boot;
        int possession = -1;
        int routeStep;
        int questionIndex;
        int blueScore;
        int redScore;
        FootballerAgent holder;

        void Start()
        {
            if (ui)
            {
                ui.AnswerSelected += OnAnswerSelected;
                ui.TeamBuzzed += OnTeamBuzzed;
            }
            BeginKickoff();
        }

        void OnDestroy()
        {
            if (!ui) return;
            ui.AnswerSelected -= OnAnswerSelected;
            ui.TeamBuzzed -= OnTeamBuzzed;
        }

        QuestionData NextQuestion()
        {
            if (questions.Count == 0)
                return new QuestionData { prompt="7 × 8 = ?", answers=new[]{"54","56","63","48"}, correctIndex=1 };
            var q = questions[questionIndex % questions.Count];
            questionIndex++;
            return q;
        }

        void BeginKickoff()
        {
            possession = -1;
            routeStep = 0;
            holder = null;
            State = MatchState.KickoffQuestion;
            ui?.ShowQuestion(NextQuestion());
            cameraRig?.SetWide();
        }

        void OnTeamBuzzed(int team)
        {
            if (State != MatchState.KickoffQuestion) return;
            possession = Mathf.Clamp(team,0,1);
            holder = ActiveTeam().RoutePlayer(0);
            routeStep = 0;
            State = MatchState.AttackingQuestion;
            ui?.ShowQuestion(NextQuestion());
            if (holder)
            {
                ball.SnapTo(holder.transform,new Vector3(0,.15f,.55f));
                cameraRig?.Track(holder.transform);
                cameraRig?.SetAttack();
            }
        }

        void OnAnswerSelected(int answerIndex)
        {
            if (State != MatchState.AttackingQuestion && State != MatchState.ShootingQuestion) return;
            var q = ui ? ui.CurrentQuestion : null;
            bool correct = q != null && q.IsCorrect(answerIndex);
            ui?.HideQuestion();

            if (State == MatchState.ShootingQuestion)
                StartCoroutine(ResolveShot(correct));
            else if (correct)
                StartCoroutine(ResolvePass());
            else
                StartCoroutine(ResolveTurnover());
        }

        TeamController ActiveTeam() => possession == 0 ? blue : red;
        TeamController OtherTeam() => possession == 0 ? red : blue;

        IEnumerator ResolvePass()
        {
            State = MatchState.PlayingPass;
            var team = ActiveTeam();
            var from = holder;
            routeStep++;
            var to = team.RoutePlayer(routeStep);
            if (!from || !to) yield break;

            from.Pass();
            to.Receive();
            cameraRig?.Track(ball.transform);

            var start = from.transform.position + Vector3.up*.22f;
            var end = to.transform.position + Vector3.up*.18f;
            yield return ball.Fly(start,end,passDuration,ball.passArc);

            holder = to;
            ball.SnapTo(holder.transform,new Vector3(0,.15f,.55f));
            cameraRig?.Track(holder.transform);

            if (routeStep >= passesBeforeShot)
            {
                State = MatchState.ShootingQuestion;
                cameraRig?.SetShot();
            }
            else State = MatchState.AttackingQuestion;

            ui?.ShowQuestion(NextQuestion());
        }

        IEnumerator ResolveTurnover()
        {
            State = MatchState.Turnover;
            var old = holder;
            possession = 1 - possession;
            routeStep = 0;
            var interceptor = ActiveTeam().RoutePlayer(0);
            if (old && interceptor)
            {
                interceptor.Tackle();
                cameraRig?.Track(interceptor.transform);
                yield return StartCoroutine(interceptor.MoveTo(old.transform.position + (interceptor.transform.position-old.transform.position).normalized*.8f,5.2f));
                holder = interceptor;
                ball.SnapTo(holder.transform,new Vector3(0,.15f,.55f));
            }
            State = MatchState.AttackingQuestion;
            cameraRig?.SetAttack();
            ui?.ShowQuestion(NextQuestion());
        }

        IEnumerator ResolveShot(bool correct)
        {
            State = MatchState.Shot;
            if (!holder) yield break;
            holder.Shoot();
            cameraRig?.Track(ball.transform);
            cameraRig?.SetShot();

            bool goal = correct && Random.value <= correctShotGoalChance;
            var defending = OtherTeam();
            var keeper = defending ? defending.Goalkeeper : null;
            Vector3 target = keeper ? keeper.transform.position + Vector3.up*1.1f : holder.transform.position + holder.transform.forward*18f;
            target += new Vector3(0, goal ? Random.Range(.1f,1.1f) : 1.8f, Random.Range(-1.6f,1.6f));

            if (keeper)
            {
                var gk = keeper.GetComponent<GoalkeeperAgent>();
                if (gk) StartCoroutine(gk.React(target,!goal));
            }

            yield return ball.Fly(holder.transform.position+Vector3.up*.2f,target,shotDuration,ball.shotArc);

            if (goal)
            {
                State = MatchState.Goal;
                if (possession==0) blueScore++; else redScore++;
                ui?.SetScore(blueScore,redScore);
                foreach(var p in ActiveTeam().players) if(p) p.Celebrate();
                yield return new WaitForSeconds(1.8f);
            }
            else
            {
                State = MatchState.Saved;
                yield return new WaitForSeconds(.9f);
            }

            State = MatchState.Restart;
            yield return new WaitForSeconds(.35f);
            BeginKickoff();
        }
    }
}
