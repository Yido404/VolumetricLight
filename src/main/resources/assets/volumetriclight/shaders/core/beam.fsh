#version 150
uniform sampler2D SceneDepth;
uniform sampler2D LightShadow;
uniform mat4 InvProjection;
uniform mat4 InvView;
uniform vec3 LightStart;
uniform vec3 LightAxis;
uniform vec3 LightRight;
uniform vec3 LightUp;
uniform vec4 LightShape;
uniform vec3 LightColor;
uniform vec3 ShadowAxis;
uniform vec3 ShadowRight;
uniform vec3 ShadowUp;
uniform vec3 ShadowOffset;
uniform vec3 ShadowShape;
in vec2 uv;
out vec4 fragColor;

float density(vec3 p) {
    float z = dot(p, LightAxis);
    if (z < 0.0 || z > LightShape.z) return 0.0;
    float radius = LightShape.x + z * LightShape.y;
    float radial = length(vec2(dot(p, LightRight), dot(p, LightUp))) / max(radius, 0.001);
    return (1.0 - smoothstep(1.0 - LightShape.w, 1.0, radial)) * (1.0 - smoothstep(0.88, 1.0, z / LightShape.z));
}
float visibility(vec3 p) {
    vec3 q = p + ShadowOffset;
    float z = dot(q, ShadowAxis);
    float radius = max(0.001, ShadowShape.x + z * ShadowShape.y);
    vec2 coord = vec2(dot(q, ShadowRight), dot(q, ShadowUp)) / radius * 0.5 + 0.5;
    if (any(lessThan(coord, vec2(0.0))) || any(greaterThan(coord, vec2(1.0)))) return 0.0;
    vec2 packedDepth = texture(LightShadow, coord).rg * 255.0;
    float wall = (packedDepth.r + packedDepth.g * 256.0) / 65535.0 * ShadowShape.z;
    return 1.0 - smoothstep(wall + 0.04, wall + 0.18, z);
}
void main() {
    float depth = texture(SceneDepth, uv).r;
    vec4 viewFar = InvProjection * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec3 ray = normalize((InvView * vec4(normalize(viewFar.xyz / viewFar.w), 0.0)).xyz);
    vec4 viewHit = InvProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float sceneDistance = depth >= 0.999999 ? 10000.0 : length(viewHit.xyz / viewHit.w);
    vec3 center = LightStart + LightAxis * LightShape.z * 0.5;
    float endRadius = LightShape.x + LightShape.z * LightShape.y;
    float bound = sqrt(endRadius * endRadius + LightShape.z * LightShape.z * 0.25);
    float b = dot(ray, center);
    float disc = b * b - dot(center, center) + bound * bound;
    if (disc <= 0.0) discard;
    float begin = max(0.0, b - sqrt(disc));
    float finish = min(sceneDistance, b + sqrt(disc));
    if (finish <= begin) discard;
    float stepSize = (finish - begin) / 64.0;
    float scatter = 0.0;
    for (int i = 0; i < 64; i++) {
        vec3 p = ray * (begin + (float(i) + 0.5) * stepSize) - LightStart;
        float d = density(p);
        if (d > 0.0) scatter += d * visibility(p) * exp(-max(0.0, dot(p, LightAxis)) / LightShape.z) * stepSize * 0.055;
    }
    float surface = 0.0;
    if (depth < 0.999999) {
        vec3 p = ray * sceneDistance - LightStart;
        vec3 normal = normalize(cross(dFdx(ray * sceneDistance), dFdy(ray * sceneDistance)));
        surface = density(p) * visibility(p) * abs(dot(normal, LightAxis)) * 0.18;
    }
    fragColor = vec4(LightColor * (min(scatter, 1.5) + surface), 0.0);
}
