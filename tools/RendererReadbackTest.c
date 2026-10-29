// Execute the production reader against IL2CPP API fixtures. These deliberately
// use unrelated object layouts: the reader must obtain all values through APIs.
#include <assert.h>
#include <stdlib.h>
#include "../native/renderer-diagnostics/library.c"

typedef struct Klass { const char *name; int size; } Klass;
typedef struct Object { int sentinel; Klass *klass; uint8_t data[1024]; } Object;
typedef struct Member { Klass *klass; const char *name; } Member;
static Klass integer = {"Int32",4}, boolean = {"Boolean",1}, single = {"Single",4};
static Klass rect = {"Rect",16}, matrix = {"Matrix4x4",64};
static Klass glass = {"GlassConfig",0}, settings = {"XREALSettings",0}, xr = {"XRSettings",0};
static Klass camera = {"Camera",0}, manager = {"SubsystemManager",0}, list = {"List`1",0};
static Klass display = {"XRDisplaySubsystem",0}, pass = {"XRRenderPass",16}, parameter = {"XRRenderParameter",16}, descriptor = {"RenderTextureDescriptor",52};
static Object camera_object = {0,&camera,{0}}, settings_object = {0,&settings,{0}}, list_object = {0,&list,{0}}, display_object = {0,&display,{0}};
static int layout, missing, threw, calls;
static Member members[10000]; static size_t member_count;
static Object objects[10000]; static size_t object_count;
static Object *box(Klass *klass, const void *value) {
    assert(object_count < 10000); Object *o = &objects[object_count++]; o->klass = klass;
    memcpy(o->data,value,(size_t)klass->size);return o;
}
static Object *i32(int value) { return box(&integer,&value); }
static Object *truth(void) { uint8_t b=1;return box(&boolean,&b); }
static Object *f32(float value) { return box(&single,&value); }
static void *fake_domain(void) { return &glass; }
static const void **fake_assemblies(void *d,size_t *n) { (void)d;static const void *a[]={&glass};*n=1;return a; }
static const void *fake_image(const void *a) { return a; }
static void *fake_type(const void *image,const char *ns,const char *name) {
    (void)image;(void)ns;
    Klass *types[]={&glass,&settings,&xr,&camera,&manager};
    for(unsigned i=0;i<sizeof(types)/sizeof(types[0]);i++)if(!strcmp(types[i]->name,name))return types[i];
    return NULL;
}
static void *fake_parent(void *c) { (void)c;return NULL; }
static const char *fake_name(void *c) { return ((Klass *)c)->name; }
static int32_t fake_size(void *c,uint32_t *align) { if(align)*align=8;return ((Klass *)c)->size; }
static void *fake_nested(void *c,void **iterator) {
    if(c!=&display||missing)return NULL;
    uintptr_t at=(uintptr_t)*iterator;*iterator=(void *)(at+1);
    return at==0?&pass:at==1?&parameter:NULL;
}
static const void *fake_method(void *c,const char *name,int count) {
    (void)count;assert(member_count<10000);
    Member *m=&members[member_count++];*m=(Member){c,name};return m;
}
static void *fake_field(void *c,const char *name) { return (void *)fake_method(c,name,0); }
static void *fake_class(void *o) { return ((Object *)o)->klass; }
static void *fake_unbox(void *o) { return ((Object *)o)->data; }
static void *fake_box(void *c,void *data) { return box(c,data); }
static void *fake_thread(void) { return &glass; }
static void *fake_field_value(void *f,void *o) {
    Member *m=f;
    if(m->klass==&glass) {
        if(!strcmp(m->name,"s_Inited"))return truth();
        return i32(layout==0?10:3);
    }
    if(m->klass==&settings) {
        if(!strcmp(m->name,"s_Settings"))return &settings_object;
        if(!strcmp(m->name,"SupportMonoMode"))return truth();
        return i32(layout==2?0:2);
    }
    if(m->klass==&manager)return &list_object;
    if(m->klass==&pass && !strcmp(m->name,"renderTargetDesc")) { uint8_t bytes[52]={0};return box(&descriptor,bytes); }
    if(m->klass==&descriptor) {
        if(strstr(m->name,"width"))return i32(1920);
        if(strstr(m->name,"height"))return i32(1080);
        if(strstr(m->name,"volumeDepth"))return i32(layout==1?2:1);
        return i32(1);
    }
    if(m->klass==&parameter) {
        int eye;memcpy(&eye,((Object *)o)->data,4);
        if(!strcmp(m->name,"textureArraySlice"))return i32(layout==1?eye:0);
        if(!strcmp(m->name,"viewport")){float r[]={0,0,1920,1080};return box(&rect,r);}
        if(!strcmp(m->name,"projection")){float mat[16]={0};mat[0]=1.2f;mat[5]=2.1f;mat[8]=eye?0.03f:-0.03f;mat[15]=1;return box(&matrix,mat);}
    }
    return NULL;
}
static void *fake_invoke(const void *target,void *o,void **args,void **exception) {
    const Member *m=target;(void)o;calls++;
    if(threw && !strcmp(m->name,"GetRenderPass")){*exception=&glass;return NULL;}
    // Any unexpected mutating/initializing call fails this fixture.
    assert(strncmp(m->name,"get_",4)==0 || !strcmp(m->name,"GetRenderPassCount") || !strcmp(m->name,"GetRenderPass")
           || !strcmp(m->name,"GetRenderParameterCount_Injected") || !strcmp(m->name,"GetRenderParameter_Injected"));
    if(!strcmp(m->name,"get_main"))return &camera_object;
    if(!strcmp(m->name,"get_Count"))return i32(1);
    if(!strcmp(m->name,"get_Item"))return &display_object;
    if(!strcmp(m->name,"GetRenderPassCount"))return i32(layout==2?2:1);
    if(!strcmp(m->name,"GetRenderPass")){memcpy(args[1],args[0],4);return NULL;}
    if(!strcmp(m->name,"GetRenderParameterCount_Injected"))return i32(layout==1?2:1);
    if(!strcmp(m->name,"GetRenderParameter_Injected")){memcpy(args[3],args[2],4);return NULL;}
    if(strstr(m->name,"Width"))return i32(1920);
    if(strstr(m->name,"Height"))return i32(1080);
    if(!strcmp(m->name,"get_aspect"))return f32(16.f/9.f);
    if(!strcmp(m->name,"get_fieldOfView"))return f32(46);
    if(!strcmp(m->name,"get_eyeTextureResolutionScale"))return f32(0.8f);
    if(!strcmp(m->name,"get_stereoRenderingMode"))return i32(layout==2?0:2);
    if(!strcmp(m->name,"get_stereoTargetEye"))return i32(layout==0?1:3);
    if(!strcmp(m->name,"get_pixelRect")){float r[]={0,0,1920,1080};return box(&rect,r);}
    if(!strcmp(m->name,"get_projectionMatrix")){float mat[16]={0};return box(&matrix,mat);}
    return truth();
}
static void setup(void) {
    domain_get=fake_domain;domain_get_assemblies=fake_assemblies;assembly_get_image=fake_image;class_from_name=fake_type;
    class_get_parent=fake_parent;class_get_nested_types=fake_nested;class_get_name=fake_name;class_value_size=fake_size;
    class_get_method_from_name=fake_method;class_get_field_from_name=fake_field;field_get_value_object=fake_field_value;
    runtime_invoke=fake_invoke;object_unbox=fake_unbox;object_get_class=fake_class;value_box=fake_box;thread_current=fake_thread;ready=true;
}
#ifndef ANDROID_FIXTURE
int main(void) {
    setup();
    for(layout=0;layout<3;layout++) {
        const char *s=capture();
        assert(strstr(s,layout==0?"GlassConfig.GlassMode=10":"GlassConfig.GlassMode=3"));
        assert(strstr(s,"width=1920") && strstr(s,"height=1080"));
        assert(strstr(s,"viewport=[0,0,1920,1080]") && strstr(s,"projection=[1.2"));
        assert(strstr(s,layout==2?"renderPassCount=2":"renderPassCount=1"));
        assert(strstr(s,layout==1?"renderParameterCount=2":"renderParameterCount=1"));
        assert((strstr(s,"textureArraySlice=1")!=NULL)==(layout==1));
        assert(!strstr(s,"view=") && !strstr(s,"unavailable"));
    }
    missing=1;assert(strstr(capture(),"value type metadata missing/oversized"));missing=0;
    threw=1;assert(strstr(capture(),"GetRenderPass=unavailable (managed exception)"));threw=0;
    assert(calls>100);
    printf("PASS renderer readback: mono, instanced stereo, multipass, projections, missing metadata and exceptions\n");
    return 0;
}
#else
// Emulator fixture exports the actual API signatures resolved by the separately
// built production diagnostic library. It substitutes Unity data, not the reader.
#define API __attribute__((visibility("default")))
API void *il2cpp_domain_get(void){return fake_domain();}
API const void **il2cpp_domain_get_assemblies(void *d,size_t *n){return fake_assemblies(d,n);}
API const void *il2cpp_assembly_get_image(const void *a){return fake_image(a);}
API void *il2cpp_class_from_name(const void *i,const char *ns,const char *n){return fake_type(i,ns,n);}
API void *il2cpp_class_get_parent(void *c){return fake_parent(c);}
API void *il2cpp_class_get_nested_types(void *c,void **i){return fake_nested(c,i);}
API const char *il2cpp_class_get_name(void *c){return fake_name(c);}
API int32_t il2cpp_class_value_size(void *c,uint32_t *a){return fake_size(c,a);}
API const void *il2cpp_class_get_method_from_name(void *c,const char *n,int a){return fake_method(c,n,a);}
API void *il2cpp_class_get_field_from_name(void *c,const char *n){return fake_field(c,n);}
API void *il2cpp_field_get_value_object(void *f,void *o){return fake_field_value(f,o);}
API void *il2cpp_runtime_invoke(const void *m,void *o,void **a,void **e){return fake_invoke(m,o,a,e);}
API void *il2cpp_object_unbox(void *o){return fake_unbox(o);}
API void *il2cpp_object_get_class(void *o){return fake_class(o);}
API void *il2cpp_value_box(void *c,void *v){return fake_box(c,v);}
API void *il2cpp_thread_current(void){return fake_thread();}
JNIEXPORT jint JNICALL Java_dev_beampro_renderfixture_RuntimeChecks_nativeCalls(JNIEnv *e,jclass c){(void)e;(void)c;return calls;}
JNIEXPORT void JNICALL Java_dev_beampro_renderfixture_RuntimeChecks_nativeLayout(JNIEnv *e,jclass c,jint mode){
    (void)e;(void)c;layout=mode;object_count=0;member_count=0;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved){(void)vm;(void)reserved;(void)capture;setup();return JNI_VERSION_1_6;}
#endif
