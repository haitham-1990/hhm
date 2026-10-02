using UnityEngine;

namespace KhutwaFootball.CameraSystem
{
    public sealed class BroadcastCameraRig : MonoBehaviour
    {
        public Camera targetCamera;
        public Transform focus;
        public Vector3 wideOffset = new Vector3(0,13,-22);
        public Vector3 attackOffset = new Vector3(0,9,-15);
        public Vector3 shotOffset = new Vector3(0,5,-9);
        public float smooth = 4.5f;
        Vector3 currentOffset;

        void Awake()
        {
            if (!targetCamera) targetCamera = Camera.main;
            currentOffset = wideOffset;
        }

        public void SetWide() => currentOffset = wideOffset;
        public void SetAttack() => currentOffset = attackOffset;
        public void SetShot() => currentOffset = shotOffset;
        public void Track(Transform t) => focus = t;

        void LateUpdate()
        {
            if (!targetCamera || !focus) return;
            var desired = focus.position + currentOffset;
            targetCamera.transform.position = Vector3.Lerp(targetCamera.transform.position, desired, 1f-Mathf.Exp(-smooth*Time.deltaTime));
            var look = focus.position + Vector3.up * 1.1f;
            var rot = Quaternion.LookRotation(look - targetCamera.transform.position,Vector3.up);
            targetCamera.transform.rotation = Quaternion.Slerp(targetCamera.transform.rotation,rot,1f-Mathf.Exp(-smooth*Time.deltaTime));
        }
    }
}
