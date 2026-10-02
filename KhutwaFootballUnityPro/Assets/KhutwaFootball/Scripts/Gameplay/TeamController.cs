using UnityEngine;

namespace KhutwaFootball.Gameplay
{
    public sealed class TeamController : MonoBehaviour
    {
        public string teamName = "Team";
        public Color primaryColor = Color.blue;
        public FootballerAgent[] players = new FootballerAgent[6];

        public FootballerAgent Goalkeeper =>
            players != null && players.Length > 0 ? players[0] : null;

        public FootballerAgent RoutePlayer(int step)
        {
            if (players == null || players.Length < 6) return null;
            int[] order = { 3, 4, 5, 2 };
            return players[order[Mathf.Clamp(step, 0, order.Length - 1)]];
        }
    }
}
