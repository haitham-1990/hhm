using System.Collections;
using UnityEngine;

namespace KhutwaFootball.Gameplay
{
    public sealed class FootballerAgent : MonoBehaviour
    {
        [Header("Identity")]
        public string displayName = "Player";
        public int shirtNumber = 1;
        public bool goalkeeper;

        [Header("Animation")]
        public Animator animator;
        public string idleState = "Idle";
        public string runState = "Run";
        public string receiveTrigger = "Receive";
        public string passTrigger = "Pass";
        public string tackleTrigger = "Tackle";
        public string shootTrigger = "Shoot";
        public string celebrateTrigger = "Celebrate";
        public string saveTrigger = "Save";

        public bool Busy { get; private set; }

        public void Face(Vector3 worldPoint)
        {
            var flat = worldPoint - transform.position;
            flat.y = 0;
            if (flat.sqrMagnitude > 0.001f)
                transform.rotation = Quaternion.LookRotation(flat.normalized, Vector3.up);
        }

        public IEnumerator MoveTo(Vector3 target, float speed)
        {
            Busy = true;
            if (animator) animator.CrossFade(runState, .12f);
            while ((transform.position - target).sqrMagnitude > .025f)
            {
                var next = Vector3.MoveTowards(transform.position, target, speed * Time.deltaTime);
                Face(next + (next - transform.position));
                transform.position = next;
                yield return null;
            }
            transform.position = target;
            if (animator) animator.CrossFade(idleState, .12f);
            Busy = false;
        }

        public void Receive()
        {
            if (animator && !string.IsNullOrWhiteSpace(receiveTrigger))
                animator.SetTrigger(receiveTrigger);
        }

        public void Pass()
        {
            if (animator) animator.SetTrigger(passTrigger);
        }

        public void Tackle()
        {
            if (animator) animator.SetTrigger(tackleTrigger);
        }

        public void Shoot()
        {
            if (animator) animator.SetTrigger(shootTrigger);
        }

        public void Celebrate()
        {
            if (animator) animator.SetTrigger(celebrateTrigger);
        }

        public void Save()
        {
            if (animator) animator.SetTrigger(saveTrigger);
        }
    }
}
