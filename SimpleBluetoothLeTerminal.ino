#include <SoftwareSerial.h>

int v;      //속도
int vmax;       //최대 속도
int Sigpin =  11 ;  // 신호 입력 핀
SoftwareSerial BTSerial(4, 5);
void  setup ()
{
  Serial.begin ( 9600 );
  pinMode (Sigpin , INPUT);
  BTSerial.begin(9600);
}
void  loop ()
{ 
  unsigned  long T;          // 주기
  double f;                 // 주파수 
  char s [ 20 ];               // Serial 출력 Length
  vmax = 0 ;
  while (digitalRead (Sigpin)); 
  while ( ! digitalRead (Sigpin));
 
  T =pulseIn (Sigpin , HIGH) + pulseIn (Sigpin , LOW); // 주기 측정
  f = 1 / ( double ) T;            // 주파수 측정
  v = int ((f * 1e6 ) /44.0 );    // 속도 측정   
  vmax = max (v, vmax);       // 속도의 Max값 측정
  sprintf (s, "% 3d km / h" , vmax);  // Serial 출력
  Serial.println (s);        // Serial 출력
  BTSerial.println(vmax);
  delay ( 500 );              // Delay 500m/s
}