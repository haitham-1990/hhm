#if UNITY_EDITOR
using System;
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace KhutwaFootball.EditorTools
{
    public static class CIAndroidBuilder
    {
        public static void Build()
        {
            ProfessionalSceneBuilder.Build();

            const string scenePath = "Assets/KhutwaFootball/Scenes/ProfessionalVerticalSlice.unity";
            if (!File.Exists(scenePath))
                throw new Exception("Vertical slice scene was not created.");

            PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, "com.khutwa.footballpro");
            PlayerSettings.bundleVersion = "0.1.0";
            PlayerSettings.Android.bundleVersionCode = 1;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel23;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
            EditorUserBuildSettings.buildAppBundle = false;

            var outputDir = "build/Android";
            Directory.CreateDirectory(outputDir);
            var outputPath = Path.Combine(outputDir, "Khutwa-Football-Pro.apk");

            var options = new BuildPlayerOptions
            {
                scenes = new[] { scenePath },
                locationPathName = outputPath,
                target = BuildTarget.Android,
                options = BuildOptions.Development
            };

            var report = BuildPipeline.BuildPlayer(options);
            if (report.summary.result != UnityEditor.Build.Reporting.BuildResult.Succeeded)
                throw new Exception($"Android build failed: {report.summary.result}");

            Debug.Log($"APK built: {outputPath} ({report.summary.totalSize} bytes)");
        }
    }
}
#endif
