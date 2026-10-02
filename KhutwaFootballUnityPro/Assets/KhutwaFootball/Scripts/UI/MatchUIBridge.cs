using System;
using KhutwaFootball.Quiz;
using UnityEngine;

namespace KhutwaFootball.UI
{
    public sealed class MatchUIBridge : MonoBehaviour
    {
        public event Action<int> AnswerSelected;
        public event Action<int> TeamBuzzed;

        public QuestionData CurrentQuestion { get; private set; }
        public int BlueScore { get; private set; }
        public int RedScore { get; private set; }

        public void ShowQuestion(QuestionData question) => CurrentQuestion = question;
        public void HideQuestion() => CurrentQuestion = null;
        public void SubmitAnswer(int index) => AnswerSelected?.Invoke(index);
        public void BuzzTeam(int teamIndex) => TeamBuzzed?.Invoke(teamIndex);

        public void SetScore(int blue, int red)
        {
            BlueScore = blue; RedScore = red;
        }
    }
}
