using System.Collections;
using UnityEngine;

namespace KhutwaFootball.Gameplay
{
    public sealed class GoalkeeperAgent : MonoBehaviour
    {
        public FootballerAgent agent;
        public Transform leftSave;
        public Transform rightSave;
        public Transform centerSave;

        public IEnumerator React(Vector3 shotTarget, bool saved)
        {
            if (!agent) yield break;
            agent.Face(shotTarget);
            if (saved) agent.Save();

            var origin = transform.position;
            Vector3 dir = shotTarget.x < transform.position.x ? Vector3.left : Vector3.right;
            var target = origin + dir * (saved ? 1.25f : .6f);
            float t=0;
            while(t<1)
            {
                t += Time.deltaTime * 4.5f;
                transform.position = Vector3.Lerp(origin,target,Mathf.Sin(Mathf.Clamp01(t)*Mathf.PI*.5f));
                yield return null;
            }
            yield return new WaitForSeconds(.35f);
            transform.position = origin;
        }
    }
}
