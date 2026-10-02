using System;
using System.Collections;
using UnityEngine;

namespace KhutwaFootball.Gameplay
{
    public sealed class BallMotor : MonoBehaviour
    {
        public AnimationCurve flight = AnimationCurve.EaseInOut(0,0,1,1);
        public float passArc = .35f;
        public float shotArc = 1.5f;
        public float spinSpeed = 900f;

        Coroutine motion;

        public void SnapTo(Transform anchor, Vector3 localOffset)
        {
            if (motion != null) StopCoroutine(motion);
            transform.SetParent(anchor);
            transform.localPosition = localOffset;
            transform.localRotation = Quaternion.identity;
        }

        public IEnumerator Fly(Vector3 start, Vector3 end, float duration, float arc, Action onComplete = null)
        {
            transform.SetParent(null);
            float t = 0f;
            while (t < 1f)
            {
                t += Time.deltaTime / Mathf.Max(.05f, duration);
                float e = Mathf.Clamp01(t);
                var p = Vector3.Lerp(start, end, e);
                p.y += Mathf.Sin(e * Mathf.PI) * arc;
                transform.position = p;
                transform.Rotate(Vector3.right, spinSpeed * Time.deltaTime, Space.Self);
                yield return null;
            }
            transform.position = end;
            onComplete?.Invoke();
        }

        public Coroutine BeginFly(Vector3 start, Vector3 end, float duration, bool shot, Action onComplete = null)
        {
            if (motion != null) StopCoroutine(motion);
            motion = StartCoroutine(Fly(start,end,duration,shot ? shotArc : passArc,onComplete));
            return motion;
        }
    }
}
