using System;
using UnityEngine;

namespace KhutwaFootball.Quiz
{
    [Serializable]
    public sealed class QuestionData
    {
        [TextArea] public string prompt;
        public string[] answers = new string[4];
        [Range(0,3)] public int correctIndex;

        public bool IsCorrect(int index) => index == correctIndex;
    }
}
