#if UNITY_EDITOR
using System.IO;
using KhutwaFootball.CameraSystem;
using KhutwaFootball.Core;
using KhutwaFootball.Gameplay;
using KhutwaFootball.Quiz;
using KhutwaFootball.UI;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace KhutwaFootball.EditorTools
{
    public static class ProfessionalSceneBuilder
    {
        [MenuItem("Khutwa Football/Create Professional Vertical Slice")]
        public static void Build()
        {
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            var root = new GameObject("KHUTWA_FOOTBALL_PRO");

            var field = GameObject.CreatePrimitive(PrimitiveType.Cube);
            field.name = "Pitch_ReplaceWithProductionAsset";
            field.transform.SetParent(root.transform);
            field.transform.position = new Vector3(0,-.15f,0);
            field.transform.localScale = new Vector3(52,.3f,32);

            var ballGo = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            ballGo.name = "Ball";
            ballGo.transform.SetParent(root.transform);
            ballGo.transform.localScale = Vector3.one * .42f;
            ballGo.transform.position = new Vector3(0,.22f,0);
            var ball = ballGo.AddComponent<BallMotor>();

            var cameraGo = new GameObject("BroadcastCamera");
            cameraGo.transform.SetParent(root.transform);
            cameraGo.tag = "MainCamera";
            var cam = cameraGo.AddComponent<Camera>();
            cameraGo.AddComponent<AudioListener>();
            var cameraRig = cameraGo.AddComponent<BroadcastCameraRig>();
            cameraRig.targetCamera = cam;
            cameraGo.transform.position = new Vector3(0,13,-22);

            var blueGo = new GameObject("BLUE_TEAM");
            var redGo = new GameObject("RED_TEAM");
            blueGo.transform.SetParent(root.transform);
            redGo.transform.SetParent(root.transform);
            var blue = blueGo.AddComponent<TeamController>();
            var red = redGo.AddComponent<TeamController>();
            blue.teamName = "الفريق الأزرق";
            red.teamName = "الفريق الأحمر";
            blue.primaryColor = new Color(.05f,.35f,.9f);
            red.primaryColor = new Color(.85f,.08f,.08f);

            blue.players = new FootballerAgent[6];
            red.players = new FootballerAgent[6];
            Vector3[] formation =
            {
                new Vector3(-23,1,0),
                new Vector3(-14,1,-8),
                new Vector3(-14,1,8),
                new Vector3(-5,1,0),
                new Vector3(4,1,-7),
                new Vector3(4,1,7)
            };

            for(int team=0; team<2; team++)
            for(int i=0; i<6; i++)
            {
                var p = GameObject.CreatePrimitive(PrimitiveType.Capsule);
                p.name = $"DEV_SLOT_{(team==0?"BLUE":"RED")}_{i+1}_REPLACE_WITH_PLAYER_PREFAB";
                p.transform.SetParent(team==0?blueGo.transform:redGo.transform);
                var pos = formation[i];
                if(team==1) pos.x = -pos.x;
                p.transform.position = pos;
                p.transform.rotation = Quaternion.Euler(0,team==0?90:-90,0);
                var agent = p.AddComponent<FootballerAgent>();
                agent.displayName = $"طالب {i+1}";
                agent.shirtNumber = i+1;
                agent.goalkeeper = i==0;
                if(i==0) p.AddComponent<GoalkeeperAgent>().agent = agent;
                if(team==0) blue.players[i]=agent; else red.players[i]=agent;
            }

            var uiGo = new GameObject("MatchUI_Bridge");
            uiGo.transform.SetParent(root.transform);
            var ui = uiGo.AddComponent<MatchUIBridge>();

            var directorGo = new GameObject("MatchDirector");
            directorGo.transform.SetParent(root.transform);
            var director = directorGo.AddComponent<MatchDirector>();
            director.blue=blue;
            director.red=red;
            director.ball=ball;
            director.cameraRig=cameraRig;
            director.ui=ui;
            director.questions.Add(new QuestionData{prompt="كم ناتج 7 × 8 ؟",answers=new[]{"54","56","63","48"},correctIndex=1});
            director.questions.Add(new QuestionData{prompt="ما عاصمة سلطنة عُمان؟",answers=new[]{"صحار","نزوى","مسقط","صلالة"},correctIndex=2});
            director.questions.Add(new QuestionData{prompt="كم ضلعًا للمسدس؟",answers=new[]{"5","6","7","8"},correctIndex=1});

            Directory.CreateDirectory("Assets/KhutwaFootball/Scenes");
            EditorSceneManager.SaveScene(scene,"Assets/KhutwaFootball/Scenes/ProfessionalVerticalSlice.unity");
            Selection.activeObject = directorGo;
            Debug.Log("Khutwa Football professional vertical slice scene created. Replace DEV_SLOT prefabs with production football assets before final build.");
        }
    }
}
#endif
