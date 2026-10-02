extends Node3D

const BLUE := 0
const RED := 1
const ROUTE := [3, 4, 5, 2]
const BLUE_COLOR := Color("#1478df")
const RED_COLOR := Color("#d7373f")
const GK_COLOR := Color("#f0c52f")

var players: Array = [[], []]
var home_positions: Array = [[], []]
var ball: MeshInstance3D
var camera: Camera3D
var camera_focus := Vector3.ZERO
var camera_close := false

var ui_layer: CanvasLayer
var score_label: Label
var timer_label: Label
var question_panel: PanelContainer
var question_label: Label
var hint_label: Label
var answers_box: GridContainer
var status_label: Label
var progress_bar: ProgressBar

var questions := [
	{"q":"كم ناتج 7 × 8 ؟","a":["54","56","63","48"],"ok":1},
	{"q":"ما عاصمة سلطنة عُمان؟","a":["صحار","نزوى","مسقط","صلالة"],"ok":2},
	{"q":"كم ناتج 45 ÷ 9 ؟","a":["4","5","6","9"],"ok":1},
	{"q":"ما الكوكب المعروف بالكوكب الأحمر؟","a":["الزهرة","المريخ","عطارد","المشتري"],"ok":1},
	{"q":"كم ضلعًا للمسدس؟","a":["5","6","7","8"],"ok":1},
	{"q":"كم ناتج 12 + 19 ؟","a":["29","30","31","32"],"ok":2}
]

var question_index := 0
var current_question: Dictionary
var buzzed_team := -1
var possession := -1
var holder_index := 3
var attack_step := 0
var scores := [0, 0]
var busy := false
var kickoff_mode := true
var shot_question := false
var match_started_ms := 0

func _ready() -> void:
	randomize()
	Engine.max_fps = 60
	_build_environment()
	_build_pitch()
	_build_stadium()
	_build_goals()
	await _build_players()
	_build_ball()
	_build_ui()
	_reset_positions()
	match_started_ms = Time.get_ticks_msec()
	_show_kickoff_question()

func _process(delta: float) -> void:
	if camera:
		var height := 9.0 if camera_close else 13.5
		var depth := 15.0 if camera_close else 24.0
		var desired := Vector3(camera_focus.x, height, camera_focus.z + depth)
		camera.global_position = camera.global_position.lerp(desired, 1.0 - exp(-4.0 * delta))
		var look := Vector3(camera_focus.x, 1.0, camera_focus.z * 0.3)
		camera.look_at(look, Vector3.UP)

	if timer_label:
		var elapsed := int((Time.get_ticks_msec() - match_started_ms) / 1000.0)
		var remain: int = max(0, 8 * 60 - elapsed)
		timer_label.text = "%02d:%02d" % [remain / 60, remain % 60]

func _build_environment() -> void:
	var world := WorldEnvironment.new()
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color("#07111f")
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color("#b9d6ef")
	env.ambient_light_energy = 0.72
	env.fog_enabled = true
	env.fog_light_color = Color("#21384c")
	env.fog_density = 0.008
	world.environment = env
	add_child(world)

	var sun := DirectionalLight3D.new()
	sun.light_color = Color("#f6fbff")
	sun.light_energy = 1.55
	sun.rotation_degrees = Vector3(-55, -25, 0)
	sun.shadow_enabled = true
	add_child(sun)

	for x in [-27.0, 27.0]:
		for z in [-18.0, 18.0]:
			var light := OmniLight3D.new()
			light.position = Vector3(x, 15, z)
			light.light_color = Color("#d9efff")
			light.light_energy = 9.0
			light.omni_range = 34.0
			light.shadow_enabled = false
			add_child(light)

func _mat(color: Color, rough := 0.82, metallic := 0.0) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.roughness = rough
	m.metallic = metallic
	return m

func _box(name_text: String, size: Vector3, pos: Vector3, color: Color) -> MeshInstance3D:
	var n := MeshInstance3D.new()
	n.name = name_text
	var mesh := BoxMesh.new()
	mesh.size = size
	n.mesh = mesh
	n.material_override = _mat(color)
	n.position = pos
	n.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	add_child(n)
	return n

func _cylinder(name_text: String, radius: float, height: float, pos: Vector3, color: Color) -> MeshInstance3D:
	var n := MeshInstance3D.new()
	n.name = name_text
	var mesh := CylinderMesh.new()
	mesh.top_radius = radius
	mesh.bottom_radius = radius
	mesh.height = height
	n.mesh = mesh
	n.material_override = _mat(color, 0.55)
	n.position = pos
	n.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	add_child(n)
	return n

func _build_pitch() -> void:
	_box("Pitch", Vector3(64, 0.22, 40), Vector3(0, -0.13, 0), Color("#176e3a"))
	for i in range(10):
		var x := -28.8 + float(i) * 6.4
		var stripe := _box("GrassStripe", Vector3(6.35, 0.012, 39.6), Vector3(x, 0.006, 0), Color("#1d7a40") if i % 2 == 0 else Color("#176c38"))
		stripe.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF

	var white := Color("#ecf4ee")
	_box("Halfway", Vector3(0.08, 0.025, 39), Vector3(0, 0.025, 0), white)
	_box("TopTouch", Vector3(63, 0.025, 0.08), Vector3(0, 0.025, -19.45), white)
	_box("BottomTouch", Vector3(63, 0.025, 0.08), Vector3(0, 0.025, 19.45), white)
	_box("LeftGoalLine", Vector3(0.08, 0.025, 39), Vector3(-31.45, 0.025, 0), white)
	_box("RightGoalLine", Vector3(0.08, 0.025, 39), Vector3(31.45, 0.025, 0), white)

	for side in [-1.0, 1.0]:
		var x := side * 26.0
		_box("PenaltyTop", Vector3(10.8, 0.025, 0.08), Vector3(side * 28.15, 0.025, -10), white)
		_box("PenaltyBottom", Vector3(10.8, 0.025, 0.08), Vector3(side * 28.15, 0.025, 10), white)
		_box("PenaltyInner", Vector3(0.08, 0.025, 20), Vector3(x, 0.025, 0), white)

	var center := MeshInstance3D.new()
	var torus := TorusMesh.new()
	torus.inner_radius = 4.85
	torus.outer_radius = 5.0
	torus.rings = 48
	torus.ring_segments = 8
	center.mesh = torus
	center.material_override = _mat(white)
	center.rotation_degrees.x = 90
	center.position.y = 0.04
	center.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(center)

func _build_stadium() -> void:
	var stand_color := Color("#141f2c")
	_box("StandNorth", Vector3(70, 7, 7), Vector3(0, 3.2, -24), stand_color)
	_box("StandSouth", Vector3(70, 7, 7), Vector3(0, 3.2, 24), stand_color)
	_box("StandWest", Vector3(7, 7, 42), Vector3(-36, 3.2, 0), stand_color)
	_box("StandEast", Vector3(7, 7, 42), Vector3(36, 3.2, 0), stand_color)

	for z in [-20.4, 20.4]:
		for i in range(11):
			var board_color := BLUE_COLOR if i % 2 == 0 else RED_COLOR
			_box("AdBoard", Vector3(5.2, 0.9, 0.12), Vector3(-26 + i * 5.2, 0.48, z), board_color)

	var crowd_colors := [Color("#d44747"), Color("#4b85da"), Color("#ded7c6"), Color("#72869c")]
	for side_z in [-22.0, 22.0]:
		for row in range(5):
			for i in range(34):
				var person := MeshInstance3D.new()
				var sphere := SphereMesh.new()
				sphere.radius = 0.12
				sphere.height = 0.24
				person.mesh = sphere
				person.material_override = _mat(crowd_colors[(i + row * 2) % crowd_colors.size()])
				person.position = Vector3(-30 + i * 1.8, 1.2 + row * 0.52, side_z + (-1 if side_z > 0 else 1) * row * 0.35)
				add_child(person)

func _build_goals() -> void:
	for side in [-1.0, 1.0]:
		var gx := side * 31.5
		_cylinder("GoalPost", 0.08, 2.6, Vector3(gx, 1.3, -4.6), Color.WHITE)
		_cylinder("GoalPost", 0.08, 2.6, Vector3(gx, 1.3, 4.6), Color.WHITE)
		var bar := _cylinder("GoalBar", 0.08, 9.2, Vector3(gx, 2.6, 0), Color.WHITE)
		bar.rotation_degrees.x = 90
		var net := _box("GoalNet", Vector3(1.1, 2.4, 9.0), Vector3(gx + side * 0.55, 1.2, 0), Color(0.75, 0.9, 1.0, 0.1))
		var net_mat := StandardMaterial3D.new()
		net_mat.albedo_color = Color(0.78, 0.92, 1, 0.12)
		net_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		net_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		net.material_override = net_mat

func _build_players() -> void:
	var packed: PackedScene = null
	if ResourceLoader.exists("res://assets/player.glb"):
		packed = load("res://assets/player.glb")

	var formation := [
		Vector3(-28, 0, 0),
		Vector3(-18, 0, -8),
		Vector3(-18, 0, 8),
		Vector3(-7, 0, 0),
		Vector3(3, 0, -7),
		Vector3(3, 0, 7)
	]

	for team in [BLUE, RED]:
		for i in range(6):
			var root: Node3D
			if packed:
				root = packed.instantiate() as Node3D
				root.scale = Vector3.ONE
				_apply_kit(root, team, i == 0)
			else:
				root = _fallback_player(team, i == 0)
			root.name = ("Blue_" if team == BLUE else "Red_") + str(i + 1)
			add_child(root)
			var p: Vector3 = formation[i]
			if team == RED:
				p.x = -p.x
			root.position = p
			root.rotation.y = PI / 2.0 if team == BLUE else -PI / 2.0
			players[team].append(root)
			home_positions[team].append(p)
			_play_anim(root, "Idle")

func _fallback_player(team: int, goalkeeper: bool) -> Node3D:
	var root := Node3D.new()
	var body := MeshInstance3D.new()
	var capsule := CapsuleMesh.new()
	capsule.radius = 0.35
	capsule.height = 1.35
	body.mesh = capsule
	body.position.y = 1.05
	body.material_override = _mat(GK_COLOR if goalkeeper else (BLUE_COLOR if team == BLUE else RED_COLOR), 0.6)
	root.add_child(body)
	var head := MeshInstance3D.new()
	var sphere := SphereMesh.new()
	sphere.radius = 0.27
	sphere.height = 0.54
	head.mesh = sphere
	head.position.y = 2.05
	head.material_override = _mat(Color("#c99774"))
	root.add_child(head)
	return root

func _apply_kit(root: Node, team: int, goalkeeper: bool) -> void:
	var meshes := root.find_children("*", "MeshInstance3D", true, false)
	for item in meshes:
		var mi := item as MeshInstance3D
		if not mi or not mi.mesh:
			continue
		for s in range(mi.mesh.get_surface_count()):
			var src := mi.mesh.surface_get_material(s)
			if not src:
				continue
			var mat := src.duplicate()
			var mat_name := String(src.resource_name).to_lower()
			if mat is BaseMaterial3D:
				if "shirt" in mat_name:
					mat.albedo_color = GK_COLOR if goalkeeper else (BLUE_COLOR if team == BLUE else RED_COLOR)
				elif "pants" in mat_name:
					mat.albedo_color = Color("#151b24") if team == RED else Color("#f2f4f7")
				elif "socks" in mat_name:
					mat.albedo_color = GK_COLOR if goalkeeper else (BLUE_COLOR if team == BLUE else RED_COLOR)
				elif "shoes" in mat_name:
					mat.albedo_color = Color("#0c0e12")
			mi.set_surface_override_material(s, mat)

func _animation_player(root: Node) -> AnimationPlayer:
	if root is AnimationPlayer:
		return root as AnimationPlayer
	var found := root.find_children("*", "AnimationPlayer", true, false)
	return found[0] as AnimationPlayer if found.size() > 0 else null

func _play_anim(root: Node, needle: String) -> void:
	var ap := _animation_player(root)
	if not ap:
		return
	var wanted := needle.to_lower()
	for name in ap.get_animation_list():
		if wanted in String(name).to_lower():
			ap.play(name, 0.15)
			return

func _build_ball() -> void:
	ball = MeshInstance3D.new()
	ball.name = "Football"
	var sphere := SphereMesh.new()
	sphere.radius = 0.32
	sphere.height = 0.64
	sphere.radial_segments = 24
	sphere.rings = 16
	ball.mesh = sphere
	ball.material_override = _mat(Color.WHITE, 0.45)
	ball.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON
	add_child(ball)
	ball.position = Vector3(0, 0.34, 0)

	camera = Camera3D.new()
	camera.name = "BroadcastCamera"
	camera.fov = 48.0
	camera.position = Vector3(0, 13.5, 24)
	add_child(camera)
	camera.current = true

func _style_box(color: Color, radius := 14) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.corner_radius_top_left = radius
	s.corner_radius_top_right = radius
	s.corner_radius_bottom_left = radius
	s.corner_radius_bottom_right = radius
	s.border_width_left = 1
	s.border_width_right = 1
	s.border_width_top = 1
	s.border_width_bottom = 1
	s.border_color = Color(1,1,1,0.15)
	return s

func _build_ui() -> void:
	ui_layer = CanvasLayer.new()
	add_child(ui_layer)

	var top := PanelContainer.new()
	top.set_anchors_preset(Control.PRESET_TOP_WIDE)
	top.offset_left = 22
	top.offset_right = -22
	top.offset_top = 12
	top.offset_bottom = 72
	top.add_theme_stylebox_override("panel", _style_box(Color(0.02,0.06,0.11,0.9), 16))
	ui_layer.add_child(top)

	var top_h := HBoxContainer.new()
	top_h.alignment = BoxContainer.ALIGNMENT_CENTER
	top_h.add_theme_constant_override("separation", 38)
	top.add_child(top_h)

	var blue_label := Label.new()
	blue_label.text = "الفريق الأزرق"
	blue_label.add_theme_color_override("font_color", Color("#6fc3ff"))
	blue_label.add_theme_font_size_override("font_size", 22)
	top_h.add_child(blue_label)

	score_label = Label.new()
	score_label.text = "0  -  0"
	score_label.add_theme_font_size_override("font_size", 34)
	top_h.add_child(score_label)

	timer_label = Label.new()
	timer_label.text = "08:00"
	timer_label.add_theme_font_size_override("font_size", 17)
	top_h.add_child(timer_label)

	var red_label := Label.new()
	red_label.text = "الفريق الأحمر"
	red_label.add_theme_color_override("font_color", Color("#ff7b78"))
	red_label.add_theme_font_size_override("font_size", 22)
	top_h.add_child(red_label)

	question_panel = PanelContainer.new()
	question_panel.set_anchors_preset(Control.PRESET_CENTER_TOP)
	question_panel.offset_left = -280
	question_panel.offset_right = 280
	question_panel.offset_top = 86
	question_panel.offset_bottom = 248
	question_panel.add_theme_stylebox_override("panel", _style_box(Color(0.02,0.075,0.13,0.84), 18))
	ui_layer.add_child(question_panel)

	var qv := VBoxContainer.new()
	qv.add_theme_constant_override("separation", 7)
	question_panel.add_child(qv)

	question_label = Label.new()
	question_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	question_label.layout_direction = Control.LAYOUT_DIRECTION_RTL
	question_label.add_theme_font_size_override("font_size", 22)
	qv.add_child(question_label)

	hint_label = Label.new()
	hint_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	hint_label.layout_direction = Control.LAYOUT_DIRECTION_RTL
	hint_label.add_theme_font_size_override("font_size", 13)
	hint_label.add_theme_color_override("font_color", Color("#b3d8ea"))
	qv.add_child(hint_label)

	answers_box = GridContainer.new()
	answers_box.columns = 2
	answers_box.add_theme_constant_override("h_separation", 8)
	answers_box.add_theme_constant_override("v_separation", 8)
	qv.add_child(answers_box)

	progress_bar = ProgressBar.new()
	progress_bar.set_anchors_preset(Control.PRESET_CENTER_BOTTOM)
	progress_bar.offset_left = -240
	progress_bar.offset_right = 240
	progress_bar.offset_top = -31
	progress_bar.offset_bottom = -18
	progress_bar.min_value = 0
	progress_bar.max_value = 3
	progress_bar.value = 0
	progress_bar.show_percentage = false
	ui_layer.add_child(progress_bar)

	status_label = Label.new()
	status_label.set_anchors_preset(Control.PRESET_CENTER_BOTTOM)
	status_label.offset_left = -260
	status_label.offset_right = 260
	status_label.offset_top = -80
	status_label.offset_bottom = -42
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	status_label.add_theme_font_size_override("font_size", 22)
	status_label.add_theme_color_override("font_color", Color.WHITE)
	status_label.visible = false
	ui_layer.add_child(status_label)

func _clear_answers() -> void:
	for child in answers_box.get_children():
		child.queue_free()

func _next_question() -> Dictionary:
	var q: Dictionary = questions[question_index % questions.size()]
	question_index += 1
	return q

func _show_kickoff_question() -> void:
	kickoff_mode = true
	shot_question = false
	buzzed_team = -1
	current_question = _next_question()
	question_label.text = current_question["q"]
	hint_label.text = "الأسرع يضغط اسم فريقه ثم يجيب"
	_clear_answers()

	var blue_btn := Button.new()
	blue_btn.text = "🔵 الأزرق يجيب"
	blue_btn.custom_minimum_size = Vector2(250, 48)
	blue_btn.add_theme_font_size_override("font_size", 18)
	blue_btn.pressed.connect(_on_buzz.bind(BLUE))
	answers_box.add_child(blue_btn)

	var red_btn := Button.new()
	red_btn.text = "🔴 الأحمر يجيب"
	red_btn.custom_minimum_size = Vector2(250, 48)
	red_btn.add_theme_font_size_override("font_size", 18)
	red_btn.pressed.connect(_on_buzz.bind(RED))
	answers_box.add_child(red_btn)
	question_panel.visible = true
	camera_close = false
	camera_focus = Vector3.ZERO

func _on_buzz(team: int) -> void:
	if busy or not kickoff_mode:
		return
	buzzed_team = team
	hint_label.text = "اختيار الإجابة للفريق " + ("الأزرق" if team == BLUE else "الأحمر")
	_show_answer_buttons()

func _show_answer_buttons() -> void:
	_clear_answers()
	for i in range(4):
		var btn := Button.new()
		btn.text = str(current_question["a"][i])
		btn.custom_minimum_size = Vector2(250, 42)
		btn.add_theme_font_size_override("font_size", 17)
		btn.pressed.connect(_on_answer.bind(i))
		answers_box.add_child(btn)

func _show_attack_question() -> void:
	current_question = _next_question()
	question_label.text = current_question["q"]
	hint_label.text = "إجابة صحيحة = استمرار الهجمة • خطأ = قطع الكرة"
	_show_answer_buttons()
	question_panel.visible = true
	shot_question = false

func _show_shot_question() -> void:
	current_question = _next_question()
	question_label.text = current_question["q"]
	hint_label.text = "إجابة صحيحة = فرصة تسديد قوية"
	_show_answer_buttons()
	question_panel.visible = true
	shot_question = true
	camera_close = true

func _on_answer(index: int) -> void:
	if busy:
		return
	var correct := index == int(current_question["ok"])

	if kickoff_mode:
		kickoff_mode = false
		possession = buzzed_team if correct else 1 - buzzed_team
		holder_index = 3
		attack_step = 0
		_snap_ball_to_holder()
		_flash_status(("استحواذ للأزرق" if possession == BLUE else "استحواذ للأحمر") if correct else "إجابة خاطئة — الكرة للفريق الآخر")
		_show_attack_question()
		return

	question_panel.visible = false
	if shot_question:
		await _shot_sequence(correct)
	elif correct:
		await _pass_sequence()
	else:
		await _turnover_sequence()

func _snap_ball_to_holder() -> void:
	var p: Node3D = players[possession][holder_index]
	var direction := 1.0 if possession == BLUE else -1.0
	ball.position = p.position + Vector3(direction * 0.65, 0.34, 0)
	camera_focus = p.position

func _pass_sequence() -> void:
	busy = true
	var team := possession
	var from_idx := holder_index
	attack_step += 1
	var to_idx: int = ROUTE[min(attack_step, ROUTE.size() - 1)]
	var from_p: Node3D = players[team][from_idx]
	var to_p: Node3D = players[team][to_idx]
	var direction := 1.0 if team == BLUE else -1.0
	var target := to_p.position + Vector3(direction * 1.8, 0, 0.55 if attack_step % 2 == 0 else -0.55)

	_play_anim(from_p, "Punch")
	_play_anim(to_p, "Run")
	_flash_status("تمريرة صحيحة — الهجمة مستمرة!")

	var mover := create_tween()
	mover.set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_IN_OUT)
	mover.tween_property(to_p, "position", target, 0.76)

	camera_focus = (from_p.position + target) * 0.5
	await _fly_ball(from_p.position + Vector3(0, 0.55, 0), target + Vector3(0, 0.42, 0), 0.76, 0.82)
	holder_index = to_idx
	_snap_ball_to_holder()
	_play_anim(from_p, "Idle")
	_play_anim(to_p, "Idle")
	progress_bar.value = attack_step
	busy = false

	if attack_step >= 3:
		_show_shot_question()
	else:
		_show_attack_question()

func _turnover_sequence() -> void:
	busy = true
	var old_holder: Node3D = players[possession][holder_index]
	var other := 1 - possession
	var interceptor_idx := 3
	var interceptor: Node3D = players[other][interceptor_idx]
	_play_anim(interceptor, "Run")
	_flash_status("قطع الكرة! هجمة مرتدة")

	var approach := old_holder.position + (interceptor.position - old_holder.position).normalized() * 0.85
	var t := create_tween()
	t.set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_IN_OUT)
	t.tween_property(interceptor, "position", approach, 0.65)
	camera_focus = old_holder.position
	await t.finished
	_play_anim(interceptor, "Punch")
	await get_tree().create_timer(0.24).timeout

	possession = other
	holder_index = interceptor_idx
	attack_step = 0
	progress_bar.value = 0
	_snap_ball_to_holder()
	_play_anim(interceptor, "Idle")
	busy = false
	camera_close = false
	_show_attack_question()

func _shot_sequence(correct: bool) -> void:
	busy = true
	var shooter: Node3D = players[possession][holder_index]
	var keeper: Node3D = players[1 - possession][0]
	var goal_x := 31.1 if possession == BLUE else -31.1
	var goal := correct and randf() < 0.84
	var target_z := randf_range(-3.4, 3.4) if goal else (7.0 if randf() > 0.5 else -7.0)
	var target_y := randf_range(0.65, 1.9) if goal else randf_range(1.8, 3.3)
	var target := Vector3(goal_x, target_y, target_z)

	_play_anim(shooter, "Punch")
	_play_anim(keeper, "Jump")
	_flash_status("تسديــــدة!" if correct else "تسديدة تحت الضغط...")
	camera_close = true
	camera_focus = target * 0.55
	await _fly_ball(shooter.position + Vector3(0, 0.55, 0), target, 0.68, 1.8)

	if goal:
		scores[possession] += 1
		score_label.text = "%d  -  %d" % [scores[BLUE], scores[RED]]
		_flash_status("⚽ هــــــــــــدف!", 1.8)
		for p in players[possession]:
			_play_anim(p, "Jump")
		await get_tree().create_timer(1.65).timeout
	else:
		_flash_status("تصــدٍ / خارج المرمى", 1.15)
		await get_tree().create_timer(0.9).timeout

	_reset_positions()
	busy = false
	_show_kickoff_question()

func _fly_ball(from: Vector3, to: Vector3, duration: float, arc: float) -> void:
	var tw := create_tween()
	tw.set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_IN_OUT)
	tw.tween_method(_set_ball_lerp.bind(from, to, arc), 0.0, 1.0, duration)
	await tw.finished
	ball.position = to

func _set_ball_lerp(t: float, from: Vector3, to: Vector3, arc: float) -> void:
	var p := from.lerp(to, t)
	p.y += sin(t * PI) * arc
	ball.position = p
	ball.rotate_x(0.23)
	ball.rotate_z(0.09)
	camera_focus = camera_focus.lerp(p, 0.14)

func _reset_positions() -> void:
	for team in [BLUE, RED]:
		for i in range(6):
			var p: Node3D = players[team][i]
			p.position = home_positions[team][i]
			p.rotation.y = PI / 2.0 if team == BLUE else -PI / 2.0
			_play_anim(p, "Idle")
	possession = -1
	holder_index = 3
	attack_step = 0
	progress_bar.value = 0 if progress_bar else 0
	ball.position = Vector3(0, 0.34, 0) if ball else Vector3.ZERO
	camera_focus = Vector3.ZERO
	camera_close = false

func _flash_status(text_value: String, seconds := 1.0) -> void:
	if not status_label:
		return
	status_label.text = text_value
	status_label.visible = true
	var token := Time.get_ticks_msec()
	status_label.set_meta("token", token)
	_hide_status_later(token, seconds)

func _hide_status_later(token: int, seconds: float) -> void:
	await get_tree().create_timer(seconds).timeout
	if status_label and int(status_label.get_meta("token", -1)) == token:
		status_label.visible = false
