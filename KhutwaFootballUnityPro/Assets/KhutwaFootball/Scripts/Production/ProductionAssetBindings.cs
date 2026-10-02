using UnityEngine;

namespace KhutwaFootball.Production
{
    [CreateAssetMenu(menuName="Khutwa Football/Production Asset Bindings")]
    public sealed class ProductionAssetBindings : ScriptableObject
    {
        [Header("Characters")]
        public GameObject blueFieldPlayerPrefab;
        public GameObject redFieldPlayerPrefab;
        public GameObject blueGoalkeeperPrefab;
        public GameObject redGoalkeeperPrefab;

        [Header("Animation")]
        public RuntimeAnimatorController fieldPlayerAnimator;
        public RuntimeAnimatorController goalkeeperAnimator;

        [Header("Environment")]
        public GameObject stadiumPrefab;
        public GameObject pitchPrefab;
        public GameObject goalPrefab;
        public GameObject crowdPrefab;

        [Header("Audio")]
        public AudioClip crowdLoop;
        public AudioClip passSfx;
        public AudioClip shotSfx;
        public AudioClip goalSfx;
        public AudioClip whistleSfx;
    }
}
