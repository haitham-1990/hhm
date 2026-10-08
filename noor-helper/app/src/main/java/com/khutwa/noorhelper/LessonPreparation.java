package com.khutwa.noorhelper;

final class LessonPreparation {
    final String title;
    final String concepts;
    final String intro;
    final String procedures;
    final String formative;
    final String summative;
    final String weeklyNote;

    LessonPreparation(String title, String concepts, String intro, String procedures,
                      String formative, String summative, String weeklyNote) {
        this.title = title;
        this.concepts = concepts;
        this.intro = intro;
        this.procedures = procedures;
        this.formative = formative;
        this.summative = summative;
        this.weeklyNote = weeklyNote;
    }

    String preview() {
        return "العنوان: " + title + "\n\n"
                + "المفاهيم:\n" + concepts + "\n\n"
                + "التهيئة / التعلم القبلي:\n" + intro + "\n\n"
                + "إجراءات سير الدرس:\n" + procedures + "\n\n"
                + "التقويم التكويني:\n" + formative + "\n\n"
                + "التقويم الختامي:\n" + summative + "\n\n"
                + "ملاحظات الخطة الأسبوعية:\n" + weeklyNote;
    }
}
